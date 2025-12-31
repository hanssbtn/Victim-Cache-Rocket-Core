package chipyard

import victimcache._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.rocket._
import freechips.rocketchip.tilelink._
import sifive.blocks.inclusivecache._
import chipyard.config._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.util._

class WithInclusiveCacheAndVC(
  nWays: Int = 8,
  capacityKB: Int = 512,
  outerLatencyCycles: Int = 40,
  subBankingFactor: Int = 4,
  hintsSkipProbe: Boolean = false,
  bankedControl: Boolean = false,
  ctrlAddr: Option[Int] = Some(InclusiveCacheParameters.L2ControlAddress),
  writeBytes: Int = 8,
  // VC Parameters
  vcEntries: Int = 8
) extends Config((site, here, up) => {
  case InclusiveCacheKey => InclusiveCacheParams(
      sets = (capacityKB * 1024)/(site(CacheBlockBytes) * nWays * up(SubsystemBankedCoherenceKey, site).nBanks),
      ways = nWays,
      memCycles = outerLatencyCycles,
      writeBytes = writeBytes,
      portFactor = subBankingFactor,
      hintsSkipProbe = hintsSkipProbe,
      bankedControl = bankedControl,
      ctrlAddr = ctrlAddr)
  case SubsystemBankedCoherenceKey => up(SubsystemBankedCoherenceKey, site).copy(coherenceManager = { context =>
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
        x.sourceId.start % 2 == 1 
        x.nodePath.last.name == "dcache.node"
      if (dcacheMMIO) None else Some(x)
    }

    val filter = LazyModule(new TLFilter(cfilter = skipMMIO))
    val l2_inner_buffer = bufInnerExterior()
    val l2_outer_buffer = bufOuterExterior()
    val cork = LazyModule(new TLCacheCork)
    val lastLevelNode = cork.node
    val vc = LazyModule(new TLVictimCache(VictimCacheParams(
      nEntries = vcEntries,
      blockBytes = sbus.blockBytes
    )))

    l2_inner_buffer.suggestName("InclusiveCache_inner_TLBuffer")
    l2_outer_buffer.suggestName("InclusiveCache_outer_TLBuffer")

    // Connect VC to the filter and inner buffer
    vc.node :*= filter.node
    l2_inner_buffer.node :*= vc.node

    l2.node :*= l2_inner_buffer.node
    l2_outer_buffer.node :*= l2.node

    /* PhysicalFilters need to be on the TL-C side of a CacheCork to prevent Acquire.NtoB -> Grant.toT */
    physicalFilter match {
      case None => lastLevelNode :*= l2_outer_buffer.node
      case Some(fp) => {
        val physicalFilter = LazyModule(new PhysicalFilter(fp.copy(controlBeatBytes = cbus.beatBytes)))
        lastLevelNode :*= physicalFilter.node :*= l2_outer_buffer.node
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

class SmallL1RocketConfig extends Config (
    new WithNPerfCounters(8) ++
    new WithNHugeCores(1) ++
    new WithNBanks(1) ++
    new WithL1DCacheSets(64) ++
    new WithL1DCacheWays(1) ++
    new WithL1ICacheSets(64) ++
    new WithL1ICacheWays(1) ++
    new AbstractConfig
)

class VictimCacheConfig extends Config (
    new WithNPerfCounters(8) ++
    new WithNHugeCores(1) ++
    new WithNBanks(1) ++
    new WithL1DCacheSets(64) ++
    new WithL1DCacheWays(1) ++
    new WithL1ICacheSets(64) ++
    new WithL1ICacheWays(1) ++
    new WithInclusiveCacheAndVC ++
    new AbstractConfig
)
