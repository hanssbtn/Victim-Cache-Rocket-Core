package chipyard

import victimcache._
import chisel3._
import chisel3.util._
import chipyard.DualRocketConfig
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy._
import freechips.rocketchip.subsystem.{CacheBlockBytes, SubsystemBankedCoherenceKey, SBUS, CBUS}
import org.chipsalliance.cde.config._
import freechips.rocketchip.tile._
import freechips.rocketchip.rocket._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.util._


class WithVictimCacheAroundL2(
  nWays: Int = 8,
  capacityKB: Int = 16,
  outerLatencyCycles: Int = 40,
  subBankingFactor: Int = 4,
  hintsSkipProbe: Boolean = false,
  bankedControl: Boolean = false,
  ctrlAddr: Option[Int] = Some(InclusiveCacheParameters.L2ControlAddress),
  writeBytes: Int = 8
) extends Config((site, here, up) => {
  case InclusiveCacheKey => 
    InclusiveCacheParams(
      sets = (capacityKB * 1024)/(site(CacheBlockBytes) * nWays * up(SubsystemBankedCoherenceKey, site).nBanks),
      ways = nWays,
      memCycles = outerLatencyCycles,
      writeBytes = writeBytes,
      portFactor = subBankingFactor,
      hintsSkipProbe = hintsSkipProbe,
      bankedControl = bankedControl,
      ctrlAddr = ctrlAddr)
  case SubsystemBankedCoherenceKey =>
    // Pull the original coherence manager (e.g., InclusiveCache)
    val parent = up(SubsystemBankedCoherenceKey)
    
    parent.copy(coherenceManager = { context =>
    implicit val p = context.p
    val sbus = context.tlBusWrapperLocationMap(SBUS)
    val cbus = context.tlBusWrapperLocationMap.lift(CBUS).getOrElse(sbus)
    val InclusiveCacheParams(
      ways,
      sets,
      writeBytes,
      portFactor,
      memCycles,
      physicalFilter,
      hintsSkipProbe,
      bankedControl,
      ctrlAddr,
      bufInnerInterior,
      bufInnerExterior,
      bufOuterInterior,
      bufOuterExterior) = p(InclusiveCacheKey)

    val l2Ctrl = ctrlAddr.map { addr =>
      InclusiveCacheControlParameters(
        address = addr,
        beatBytes = cbus.beatBytes,
        bankedControl = bankedControl)
    }
    val l2 = LazyModule(new InclusiveCache(
      CacheParameters(
        level = 2,
        ways = ways,
        sets = sets,
        blockBytes = sbus.blockBytes,
        beatBytes = sbus.beatBytes,
        hintsSkipProbe = hintsSkipProbe),
      InclusiveCacheMicroParameters(
        writeBytes = writeBytes,
        portFactor = portFactor,
        memCycles = memCycles,
        innerBuf = bufInnerInterior,
        outerBuf = bufOuterInterior),
      l2Ctrl))

    def skipMMIO(x: TLClientParameters) = {
      val dcacheMMIO =
        x.requestFifo &&
        x.sourceId.start % 2 == 1 && // 1 => dcache issues acquires from another master
        x.nodePath.last.name == "dcache.node"
      if (dcacheMMIO) None else Some(x)
    }

    val filter = LazyModule(new TLFilter(cfilter = skipMMIO))
    val l2_inner_buffer = bufInnerExterior()
    val l2_outer_buffer = bufOuterExterior()
    val cork = LazyModule(new TLCacheCork)
    val lastLevelNode = cork.node
    val vc = LazyModule(new VictimCache(new VictimCacheParams(lineBytes = sbus.blockBytes)))

    l2_inner_buffer.suggestName("InclusiveCache_inner_TLBuffer")
    l2_outer_buffer.suggestName("InclusiveCache_outer_TLBuffer")

    l2_inner_buffer.node :*= filter.node
    l2.node :*= l2_inner_buffer.node
    l2_outer_buffer.node :*= l2.node

    // 4. Insert Victim Cache on the MEMORY SIDE (Between Outer Buffer and LastLevel)
    // Flow: L2 -> OuterBuffer -> VictimCache -> (PhysicalFilter) -> SystemBus
    physicalFilter match {
      case None => 
        lastLevelNode :*= vc.victimCacheNode :*= l2_outer_buffer.node
      case Some(fp) => {
        val physicalFilter = LazyModule(new PhysicalFilter(fp.copy(controlBeatBytes = cbus.beatBytes)))
        lastLevelNode :*= physicalFilter.node :*= vc.victimCacheNode :*= l2_outer_buffer.node
        
        physicalFilter.controlNode := cbus.coupleTo("physical_filter") {
          TLBuffer(1) := TLFragmenter(cbus, Some("LLCPhysicalFilter")) := _
        }
      }
    }

    l2.ctrls.foreach {
      _.ctrlnode := cbus.coupleTo("l2_ctrl") { TLBuffer(1) := TLFragmenter(cbus, Some("LLCCtrl")) := _ }
    }

    ElaborationArtefacts.add("l2.json", l2.module.json)
    (filter.node, lastLevelNode, None)
  })

})


class WithVictimCacheConfig extends Config (
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++         // single rocket-core
  new freechips.rocketchip.subsystem.WithNBanks(2) ++
  new chipyard.config.WithNPerfCounters ++
  new WithVictimCacheAroundL2 ++
  new chipyard.config.AbstractConfig
)


