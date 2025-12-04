package victimcache

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.diplomacy.{RegionType, TransferSizes}
import freechips.rocketchip.subsystem.CacheBlockBytes

class VictimCache(params: VictimCacheParams)(implicit p: Parameters) extends LazyModule {
    suggestName("VictimCache")
    
    val victimCacheNode = TLAdapterNode(
        clientFn  = { in => in },
        managerFn = { out => 
            val cacheBlockBytes = p(CacheBlockBytes)
            out.v1copy(
                endSinkId = out.endSinkId + 1,
                managers = out.managers.map { m => 
                    m.v1copy(
                        regionType = RegionType.CACHED,
                        supportsAcquireB = TransferSizes(1, cacheBlockBytes), 
                        supportsAcquireT = TransferSizes(1, cacheBlockBytes),
                        alwaysGrantsT = false
                    )
                }
            ) 
        }
    )

    lazy val module = new LazyModuleImp(this) {
        (victimCacheNode.in zip victimCacheNode.out).zipWithIndex.foreach { 
            case (((in, edgeIn), (out, edgeOut)), i) =>
            
            val mySinkId = edgeOut.manager.endSinkId.U
            val beatBytes = edgeIn.manager.beatBytes
            val beatsPerLine = params.lineBytes / beatBytes
            val numLines = params.numLines
            val lineBytes = params.lineBytes
            val idxWidth = log2Ceil(numLines)
            val tagWidth = 64 - log2Ceil(lineBytes) 

            // Performance Counters
            val hit_count  = RegInit(0.U(64.W))
            val evict_count = RegInit(0.U(64.W))
            val cycle_count = RegInit(0.U(64.W))
            cycle_count := cycle_count + 1.U

            // Print Stats every 100k cycles
            when (cycle_count % 100000.U === 0.U) {
                printf("--- [VC STATS] Cycle: %d ---\n", cycle_count)
                printf("    Hits:   %d\n", hit_count)
                printf("    Evicts: %d\n", evict_count)
            }

            val validArray = RegInit(VecInit(Seq.fill(numLines)(false.B)))
            val tagArray   = Reg(Vec(numLines, UInt(tagWidth.W)))
            val dataArray  = Reg(Vec(numLines, Vec(beatsPerLine, UInt((beatBytes * 8).W))))

            def getIndex(addr: UInt): UInt = addr(log2Ceil(lineBytes) + idxWidth - 1, log2Ceil(lineBytes))
            def getTag(addr: UInt): UInt   = addr >> log2Ceil(lineBytes)

            // ==============================================================================
            // CHANNEL C (Evictions)
            // ==============================================================================
            val isReleaseData = in.c.valid && (in.c.bits.opcode === TLMessages.ReleaseData)
            val c_idx = getIndex(in.c.bits.address)
            val c_tag = getTag(in.c.bits.address)
            val (c_first, c_last, _, c_beat) = edgeIn.count(in.c)
            
            when (in.c.fire && c_first && isReleaseData) {
                printf("[VC] STORE EVICTION: Idx=%d Tag=%x\n", c_idx, c_tag)
                evict_count := evict_count + 1.U
            }

            when (isReleaseData && in.c.fire) {
                dataArray(c_idx)(c_beat) := in.c.bits.data
                when (c_last) {
                    tagArray(c_idx)    := c_tag
                    validArray(c_idx)  := true.B
                }
            }
            out.c <> in.c 

            // ==============================================================================
            // CHANNEL A (Requests)
            // ==============================================================================
            val a_idx = getIndex(in.a.bits.address)
            val a_tag = getTag(in.a.bits.address)
            val isAcquire = in.a.bits.opcode === TLMessages.AcquireBlock
            val hit = validArray(a_idx) && (tagArray(a_idx) === a_tag) && isAcquire
            val saved_req_params = RegEnable(in.a.bits, in.a.fire && hit)

            val s_idle :: s_replay :: s_wait_ack :: Nil = Enum(3)
            val state = RegInit(s_idle)
            val replay_beat = RegInit(0.U(log2Ceil(beatsPerLine + 1).W))
            
            val got_ack = RegInit(false.B)
            val is_my_id = in.e.bits.sink === mySinkId
            val incoming_ack_now = in.e.valid && is_my_id
            val vc_fired_d = in.d.fire && (in.d.bits.sink === mySinkId)

            // Watchdog for stuck replay
            val watchdog = RegInit(0.U(32.W))
            when (state =/= s_idle) { watchdog := watchdog + 1.U } .otherwise { watchdog := 0.U }
            when (watchdog > 5000.U) {
                printf("[VC-FATAL] Stuck in state %d for 5000 cycles!\n", state)
                watchdog := 0.U
            }

            when (incoming_ack_now) { got_ack := true.B }

            when (state === s_idle) {
                got_ack := false.B 
                when (in.a.valid && hit) {
                    printf("[VC-HIT] Replaying Addr: 0x%x (Idx: %d)\n", in.a.bits.address, a_idx)
                    hit_count := hit_count + 1.U
                    state := s_replay
                    replay_beat := 0.U
                    validArray(a_idx) := false.B 
                }
            } .elsewhen (state === s_replay) {
                when (vc_fired_d) { 
                    replay_beat := replay_beat + 1.U
                    when (replay_beat === (beatsPerLine - 1).U) {
                        when (got_ack || incoming_ack_now) {
                            state := s_idle
                            got_ack := false.B
                        } .otherwise {
                            state := s_wait_ack 
                        }
                    }
                }
            } .elsewhen (state === s_wait_ack) {
                 when (got_ack || incoming_ack_now) {
                     state := s_idle
                     got_ack := false.B
                 }
            }

            out.a.valid := in.a.valid && !hit 
            out.a.bits  := in.a.bits
            in.a.ready  := Mux(hit, state === s_idle, out.a.ready)

            // ==============================================================================
            // CHANNEL D (Response) - FULLY LOCKED ARBITER
            // ==============================================================================
            val my_d_bits = edgeIn.Grant(
                fromSink = mySinkId,
                toSource = saved_req_params.source,
                lgSize   = saved_req_params.size,
                capPermissions = TLPermissions.toT, 
                data     = dataArray(getIndex(saved_req_params.address)(idxWidth-1, 0))(replay_beat) 
            )

            val sending_replay = state === s_replay
            
            // 1. Track Memory Burst Status
            // We can rely on edgeOut to count memory response beats
            val (mem_first, mem_last, _, _) = edgeOut.count(out.d)
            val mem_locked = RegInit(false.B)
            
            when (out.d.fire) {
                when (mem_first && !mem_last) { mem_locked := true.B }
                when (mem_last)               { mem_locked := false.B }
            }

            // 2. Track VC Burst Status
            // If we have sent beat 0, we are locked until we finish.
            val vc_locked = sending_replay && (replay_beat > 0.U)

            // 3. Arbitration Logic
            // Memory wins if: It is already locked, OR (It is valid AND VC isn't locked)
            val memory_wins = mem_locked || (out.d.valid && !vc_locked)

            when (memory_wins) {
                // Connect Memory
                in.d <> out.d
                
                // DEBUG: Check if memory is responding
                if (true) { // Enable for verbose debug
                   when(out.d.fire) { printf("[VC-MEM] Pass-thru D Op:%x\n", out.d.bits.opcode) }
                }
            } .otherwise {
                // Connect VC
                in.d.valid := sending_replay
                in.d.bits  := my_d_bits
                
                // Block Memory completely
                out.d.ready := false.B 
            }

            // ==============================================================================
            // CHANNEL E (GrantAck)
            // ==============================================================================
            in.e.ready  := Mux(is_my_id, true.B, out.e.ready)
            out.e.valid := in.e.valid && !is_my_id
            out.e.bits  := in.e.bits

            in.b <> out.b
        }
    }
}

case class VictimCacheParams(
    numLines: Int = 256,
    lineBytes: Int = 64 
)