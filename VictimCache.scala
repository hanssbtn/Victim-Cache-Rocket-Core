package victimcache

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.diplomacy.IdRange
import freechips.rocketchip.diplomacy.TransferSizes

case class VictimCacheParams(
  nEntries: Int = 8,
  blockBytes: Int = 64
)

class TLVictimCache(params: VictimCacheParams)(implicit p: Parameters) extends LazyModule {

  val node = TLAdapterNode(
    clientFn  = { cp => 
      val vcIdRange = IdRange(cp.endSourceId, cp.endSourceId + 1)

      // 2. Define a Virtual Client for the Victim Cache
      val vcClient = TLMasterParameters.v1(
        name     = "victim-cache-internal",
        sourceId = vcIdRange,
        supportsProbe = TransferSizes(params.blockBytes, params.blockBytes)
      )

      cp.v1copy(clients = cp.clients :+ vcClient) },
    managerFn = { mp => 
      println(s"mp.endSinkId + params.nEntries: ${mp.endSinkId} + ${params.nEntries}")
      mp.v1copy(managers = mp.managers.map(m => m.v1copy(
        alwaysGrantsT = false 
      )),
        endSinkId = mp.endSinkId + params.nEntries
      )
    }
  )

  lazy val module = new LazyModuleImp(this) {
    val (in, edgeIn) = node.in(0)
    val (out, edgeOut) = node.out(0)
    val cycle = RegInit(0.U(64.W))
    println(s"\n=== [TLVictimCache] Node Map (Edge In) ===")
    edgeIn.client.clients.foreach { c =>
      println(f"  Client: ${c.name}%-30s | Source IDs: [${c.sourceId.start}%d, ${c.sourceId.end - 1}%d]")
    }
    edgeIn.manager.managers.foreach { m =>
      val addrStr = m.address.headOption.map(_.toString).getOrElse("Global")
      println(f"  Manager: ${m.name}%-30s | Address: $addrStr")
    }
    println("==========================================\n")

    println(s"\n=== [TLVictimCache] Node Map (Edge OUT) ===")
    
    println("--- Clients (Requestors) ---")
    edgeOut.client.clients.foreach { c =>
      println(f"  Client: ${c.name}%-30s | Source IDs: [${c.sourceId.start}%d, ${c.sourceId.end - 1}%d]")
    }

    println("\n--- Managers (Responders) ---")
    edgeOut.manager.managers.foreach { m =>
      val addrStr = m.address.headOption.map(_.toString).getOrElse("Global")
      println(f"  Manager: ${m.name}%-30s | Address: $addrStr")
    }
    
    def connectLockingArbiter[T <: Data](
      inputs: Seq[DecoupledIO[T]],
      output: DecoupledIO[T],
      getTotalBeats: T => UInt 
    ): Unit = {
      val n = inputs.size
      val arb = Module(new RRArbiter(output.bits.cloneType, n))
      
      val locked  = RegInit(false.B)
      val lockIdx = RegInit(0.U(log2Ceil(n).W))
      val beatsLeft = RegInit(0.U(9.W)) 
      for (i <- 0 until n) {
        val is_owner = locked && lockIdx === i.U
        val is_allowed = !locked || is_owner
        
        arb.io.in(i).valid := inputs(i).valid && is_allowed
        arb.io.in(i).bits  := inputs(i).bits
        inputs(i).ready    := arb.io.in(i).ready && is_allowed
      }

      output <> arb.io.out

      when (output.fire) {
        val total = getTotalBeats(output.bits)
        when (!locked) {
          when (total > 0.U) {
            locked := true.B
            lockIdx := arb.io.chosen
            beatsLeft := total
          }
        } .otherwise {
          beatsLeft := beatsLeft - 1.U
          when (beatsLeft === 1.U) {
            locked := false.B // Unlock on the last beat
          }
        }
      }
    }

    val addrBits = edgeIn.bundle.addressBits
    val dataBits = edgeIn.bundle.dataBits
    val sourceBits = edgeIn.bundle.sourceBits
    val beatsPerBlock = params.blockBytes / (dataBits / 8)
    val blockOffsetBits = log2Ceil(params.blockBytes)
    val tagBits = addrBits - blockOffsetBits
    val sinkBits = edgeIn.bundle.sinkBits
    val vcSinkIdOffset = WireInit(edgeOut.manager.endSinkId.U(sinkBits.W))
    val vcClientParams = edgeOut.client.clients.find(_.name == "victim-cache-internal").getOrElse(
      throw new Exception("VC Client not found in edgeOut")
    )
    
    val vcEvictSourceId = vcClientParams.sourceId.start.U

    // ==============================================================================
    // CORE LOGIC
    // ==============================================================================

    class VCMeta extends Bundle {
      val tag = UInt(tagBits.W)
      val source = UInt(sourceBits.W) 
      val valid = Bool()
      val dirty = Bool()
    }

    val meta = RegInit(VecInit(Seq.fill(params.nEntries)(0.U.asTypeOf(new VCMeta))))
    val data = Reg(Vec(params.nEntries, Vec(beatsPerBlock, UInt(dataBits.W))))
    val victimPtr = RegInit(0.U(log2Ceil(params.nEntries).W))

    // DEBUGGING
    val perf_vc_hits   = RegInit(0.U(32.W))
    val perf_vc_misses = RegInit(0.U(32.W))
    val perf_vc_allocs = RegInit(0.U(32.W))
    val perf_vc_probes = RegInit(0.U(32.W))

    // ---------------------------------------------------------
    // 1. Channel A: Requests (CPU -> VC)
    // ---------------------------------------------------------
    val a_addr = in.a.bits.address
    val a_tag  = a_addr >> blockOffsetBits.U 

    val is_acquire = in.a.bits.opcode === TLMessages.AcquireBlock || 
                     in.a.bits.opcode === TLMessages.AcquirePerm

    val hitVec = VecInit(meta.map(m => m.valid && m.tag === a_tag))
    val hit    = hitVec.asUInt.orR
    val hitIdx = PriorityEncoder(hitVec)
    
    val stall_a_for_hit = hit && is_acquire
    val saved_hitIdx = RegEnable(hitIdx, 0.U, in.a.fire && stall_a_for_hit)
  
    val any_valid = VecInit(meta.map(_.valid)).asUInt.orR
    when (in.a.fire) {
      when (stall_a_for_hit) {
        perf_vc_hits := perf_vc_hits + 1.U
        printf("[VC-HIT]  Cycle: %d | Addr: %x | Tag: %x | Index: %d\n", cycle, a_addr, a_tag, saved_hitIdx)
      } .elsewhen (is_acquire && any_valid) {
        perf_vc_misses := perf_vc_misses + 1.U
        printf("[VC-MISS]  Cycle: %d | Addr: %x | Tag: %x | Index: %d\n", cycle, a_addr, a_tag, saved_hitIdx)
      }
    }

    val sA_Idle :: sA_Reading :: Nil = Enum(2)
    val stateA = RegInit(sA_Idle)
    
    val readBeatCnt = RegInit(0.U(log2Ceil(beatsPerBlock+1).W))
    val saved_a_req = Reg(new TLBundleA(edgeIn.bundle))
    val waiting_for_ack = RegInit(false.B) 
    
    when (in.a.fire && stall_a_for_hit) {
      stateA := sA_Reading
      readBeatCnt := 0.U
      saved_a_req := in.a.bits
      meta(saved_hitIdx).valid := false.B 
      when (saved_a_req.opcode === TLMessages.AcquireBlock || 
            saved_a_req.opcode === TLMessages.AcquirePerm) {
        waiting_for_ack := true.B
      }
    }
    val is_data_response = saved_a_req.opcode === TLMessages.AcquireBlock 
    val total_beats = Mux(is_data_response, beatsPerBlock.U, 1.U)

    out.a.valid := in.a.valid && !stall_a_for_hit
    out.a.bits  := in.a.bits
    in.a.ready  := Mux(stall_a_for_hit, stateA === sA_Idle && !waiting_for_ack, out.a.ready)

    // ---------------------------------------------------------
    // 2. Channel C: Eviction Handling (L1 -> VC)
    // ---------------------------------------------------------
    val c_q = Module(new Queue(new TLBundleC(edgeIn.bundle), 2))
    c_q.io.enq <> in.c

    val is_release_data = c_q.io.deq.bits.opcode === TLMessages.ReleaseData
    val allocate_vc = is_release_data && (
                      c_q.io.deq.bits.param === TLPermissions.TtoN || 
                      c_q.io.deq.bits.param === TLPermissions.BtoN
                    )

    val sC_Idle :: sC_Evicting :: sC_Refilling :: Nil = Enum(3)
    val stateC = RegInit(sC_Idle)
    val c_beat = RegInit(0.U(log2Ceil(beatsPerBlock+1).W))

    val send_release_ack = RegInit(false.B)

    // Allocation Logic
    val emptyVec = VecInit(meta.map(m => !m.valid))
    val hasEmpty = emptyVec.asUInt.orR
    val emptyIdx = PriorityEncoder(emptyVec)
    val allocIdx = RegEnable(Mux(hasEmpty, emptyIdx, victimPtr), stateC === sC_Idle)

    // --- C-Channel Arbitration ---
    val c_in_0 = Wire(Decoupled(new TLBundleC(edgeOut.bundle))) // From Probes
    val c_in_1 = Wire(Decoupled(new TLBundleC(edgeOut.bundle))) // Forwarded Releases
    val c_in_2 = Wire(Decoupled(new TLBundleC(edgeOut.bundle))) // VC Evictions

    connectLockingArbiter(
      Seq(c_in_0, c_in_1, c_in_2),
      out.c,
      (c: TLBundleC) => edgeOut.numBeats1(c)
    )

    val fwd_release = c_q.io.deq.valid && !allocate_vc
    c_in_1.valid := fwd_release
    c_in_1.bits := c_q.io.deq.bits

    val c_fire = c_q.io.deq.valid && allocate_vc
    val can_alloc = !send_release_ack 

    c_q.io.deq.ready := (c_in_1.ready && fwd_release) || 
                        (stateC === sC_Refilling) 

    when (c_fire && stateC === sC_Idle && can_alloc) {
      val alloc_addr = c_q.io.deq.bits.address
      val alloc_tag  = alloc_addr >> blockOffsetBits.U
      printf("[VC-ALLOC] Cycle: %d | Addr: %x | Tag: %x | VictimPtr: %d\n", cycle, alloc_addr, alloc_tag, victimPtr)
      when (meta(Mux(hasEmpty, emptyIdx, victimPtr)).valid) {
        stateC := sC_Evicting
        c_beat := 0.U
      }.otherwise {
        stateC := sC_Refilling
        c_beat := 0.U
      }
    }

    val evict_msg = edgeOut.Release(
      fromSource = vcEvictSourceId,
      toAddress  = meta(allocIdx).tag << blockOffsetBits.U,
      lgSize     = log2Ceil(params.blockBytes).U,
      shrinkPermissions = Mux(meta(allocIdx).dirty, TLPermissions.TtoN, TLPermissions.BtoN),
      data        = data(allocIdx)(c_beat)
    )._2 

    c_in_2.valid := stateC === sC_Evicting
    c_in_2.bits := evict_msg

    when (c_in_2.fire) {
      c_beat := c_beat + 1.U
      when (c_beat === (beatsPerBlock.U - 1.U)) {
        stateC := sC_Refilling
        c_beat := 0.U
        victimPtr := victimPtr + 1.U
      }
    }
    
    val latch_info = c_fire && stateC === sC_Idle && can_alloc
    val saved_c_req_source = RegEnable(c_q.io.deq.bits.source, latch_info)
    val saved_c_req_size   = RegEnable(c_q.io.deq.bits.size,   latch_info)
    val saved_c_req_addr   = RegEnable(c_q.io.deq.bits.address, latch_info)
    val saved_c_req_param  = RegEnable(c_q.io.deq.bits.param,  latch_info)

    when (stateC === sC_Refilling && c_q.io.deq.valid) {
      data(allocIdx)(c_beat) := c_q.io.deq.bits.data
      c_beat := c_beat + 1.U
      when (c_beat === (beatsPerBlock.U - 1.U)) {
        stateC := sC_Idle
        meta(allocIdx).valid := true.B
        meta(allocIdx).tag := saved_c_req_addr >> blockOffsetBits.U
        meta(allocIdx).source := saved_c_req_source
        meta(allocIdx).dirty := (saved_c_req_param === TLPermissions.TtoN)
        send_release_ack := true.B
      }
    }

    // ---------------------------------------------------------
    // 3. Channel D: Responses (VC -> L1)
    // ---------------------------------------------------------
    val d_in_0 = Wire(Decoupled(new TLBundleD(edgeIn.bundle))) // From L2 (Passthrough)
    val d_in_1 = Wire(Decoupled(new TLBundleD(edgeIn.bundle))) // VC Hit Data
    val d_in_2 = Wire(Decoupled(new TLBundleD(edgeIn.bundle))) // Release Ack

    connectLockingArbiter(
      Seq(d_in_0, d_in_1, d_in_2),
      in.d,
      (d: TLBundleD) => edgeIn.numBeats1(d)
    )
    
    val is_release_ack = out.d.bits.opcode === TLMessages.ReleaseAck
                         
    val is_vc_evict_ack = out.d.valid && 
                          (out.d.bits.source === vcEvictSourceId) && 
                          is_release_ack

    out.d.ready := Mux(is_vc_evict_ack, true.B, d_in_0.ready)

    d_in_0.valid := out.d.valid && !is_vc_evict_ack
    d_in_0.bits  := out.d.bits

    val vc_d_valid = stateA === sA_Reading
    val grant = edgeIn.Grant(
      fromSink = vcSinkIdOffset + saved_hitIdx,
      toSource = saved_a_req.source,
      lgSize   = saved_a_req.size,
      capPermissions = Mux(meta(saved_hitIdx).dirty, TLPermissions.toT, TLPermissions.toB),
      data = data(saved_hitIdx)(readBeatCnt)
    )
    val grantNoData = edgeIn.Grant(
      fromSink = vcSinkIdOffset + saved_hitIdx,
      toSource = saved_a_req.source,
      lgSize   = saved_a_req.size,
      capPermissions = Mux(meta(saved_hitIdx).dirty, TLPermissions.toT, TLPermissions.toB)
    )
    val ackData = edgeIn.AccessAck(saved_a_req, data(saved_hitIdx)(readBeatCnt))

    val response_msg = MuxLookup(saved_a_req.opcode, grant)(Seq(
        TLMessages.AcquirePerm  -> grantNoData,
        TLMessages.AcquireBlock -> grant,
        TLMessages.Get          -> ackData
    ))
    
    d_in_1.valid := vc_d_valid
    d_in_1.bits := response_msg

    when (d_in_1.fire) {
      readBeatCnt := readBeatCnt + 1.U
      when (readBeatCnt === (total_beats - 1.U)) {
        stateA := sA_Idle
        readBeatCnt := 0.U
      }
    }

    val releaseAck = edgeIn.ReleaseAck(
      toSource = saved_c_req_source,
      lgSize   = saved_c_req_size,
      denied   = false.B
    ) 

    d_in_2.valid := send_release_ack
    d_in_2.bits := releaseAck
    when (d_in_2.fire) {
      send_release_ack := false.B
    }
    
    // ---------------------------------------------------------
    // 4. Channel B: Probes (VC -> L1)
    // ---------------------------------------------------------
    val b_addr = out.b.bits.address
    val b_tag = b_addr >> blockOffsetBits.U 
    val probe_hitVec = VecInit(meta.map(m => m.valid && m.tag === b_tag))
    val probe_hit = probe_hitVec.asUInt.orR
    val probe_idx = PriorityEncoder(probe_hitVec)

    in.b.valid := out.b.valid && !probe_hit
    in.b.bits := out.b.bits
    
    val sB_Idle :: sB_Responding :: Nil = Enum(2)
    val stateB = RegInit(sB_Idle)
    val b_beat = RegInit(0.U(log2Ceil(beatsPerBlock+1).W))

    val saved_probe_source = RegEnable(out.b.bits.source, out.b.valid && probe_hit && stateB === sB_Idle)
    val saved_probe_size   = RegEnable(out.b.bits.size,   out.b.valid && probe_hit && stateB === sB_Idle)
    val saved_probe_addr   = RegEnable(b_addr,            out.b.valid && probe_hit && stateB === sB_Idle)

    out.b.ready := Mux(probe_hit, stateB === sB_Idle, in.b.ready)

    when (out.b.fire && probe_hit) {
      stateB := sB_Responding
      b_beat := 0.U
      printf("[VC-PROBE] Cycle: %d | Addr: %x | Tag: %x | Hit Index: %d\n", cycle, b_addr, b_tag, probe_idx)
    }

    val probe_ack_data = edgeOut.ProbeAck(
      fromSource = saved_probe_source, 
      toAddress = saved_probe_addr,
      lgSize = saved_probe_size,
      reportPermissions = TLPermissions.toN,
      data = data(probe_idx)(b_beat) 
    )

    // Note: C-channel probe acks (c_in_0) are connected to the locking arbiter up top
    c_in_0.valid := stateB === sB_Responding
    c_in_0.bits := probe_ack_data
    
    when (c_in_0.fire) {
      b_beat := b_beat + 1.U
      when (b_beat === (beatsPerBlock.U - 1.U)) {
        stateB := sB_Idle
        meta(probe_idx).valid := false.B 
      }
    }

    // ---------------------------------------------------------
    // 5. Channel E: GrantAcks
    // ---------------------------------------------------------

   val is_vc_sink_id = in.e.bits.sink >= vcSinkIdOffset
    out.e.valid := in.e.valid && !is_vc_sink_id
    out.e.bits  := in.e.bits

    in.e.ready := Mux(is_vc_sink_id, true.B, out.e.ready)
    when (in.e.fire && is_vc_sink_id) {
        waiting_for_ack := false.B
    }

    cycle := cycle + 1.U
  }
}