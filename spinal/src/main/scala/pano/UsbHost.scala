
package pano

import spinal.core._
import spinal.lib._
import spinal.lib.io._
import spinal.lib.bus.simple._
import spinal.lib.bus.misc._
import spinal.lib.bus.amba3.apb._


object UsbHost {
    // 8 bits -> 64 registers
    // We need 32 registers for MAX3421E compatibility. The other registers are for
    // additional status and debug.
    def getApb3Config() = Apb3Config(addressWidth = 7, dataWidth=32)

    // RX:      2x 64 bytes. Address: 0, 64
    // TX:      2x 64 bytes. Address: 128, 192
    // Setup:   8 bytes.     ADdress: 256
    def getFifoMemoryBusConfig() = PipelinedMemoryBusConfig(addressWidth = 9, dataWidth = 8)

    // Supported host-only registers as defined by MAX3421E SPI-to-USB chip
    def RCVFIFO_ADDR                = 1
    def SNDFIFO_ADDR                = 2
    def SUDFIFO_ADDR                = 4
    def RCVBC_ADDR                  = 6
    def SNDBC_ADDR                  = 7

    def USBIRQ_ADDR                 = 13
    def USBIEN_ADDR                 = 14
    def USBCTL_ADDR                 = 15
    def CPUCTL_ADDR                 = 16
    def PINCTL_ADDR                 = 17
    def REVISION_ADDR               = 18
    def HIRQ_ADDR                   = 25
    def HIEN_ADDR                   = 26
    def MODE_ADDR                   = 27
    def PER_ADDR                    = 28
    def HCTL_ADDR                   = 29
    def HXFR_ADDR                   = 30
    def HRSL_ADDR                   = 31

    object HostXferType extends SpinalEnum {
        val SETUP, BULK_IN, BULK_OUT, HS_IN, HS_OUT, ISO_IN, ISO_OUT = newElement()
        defaultEncoding = SpinalEnumEncoding("staticEncoding")(
            SETUP       -> 0x1,
            BULK_IN     -> 0x0,
            BULK_OUT    -> 0x2,
            HS_IN       -> 0x8,     // Handshake reply packet after control DATA OUT
            HS_OUT      -> 0xa,     // Handshake reply packet after control DATA IN
            ISO_IN      -> 0x4,
            ISO_OUT     -> 0x6
        )
    }

    object HostXferResult extends SpinalEnum {
        val SUCCESS     = newElement()
        val BUSY        = newElement()
        val BADREQ      = newElement()
        val UNDEF       = newElement()
        val NAK         = newElement()
        val STALL       = newElement()
        val TOGERR      = newElement()
        val WRONGPID    = newElement()
        val BADBC       = newElement()
        val PIDERR      = newElement()
        val PKTERR      = newElement()
        val CRCERR      = newElement()
        val KERR        = newElement()
        val JERR        = newElement()
        val TIMEOUT     = newElement()
        val BABBLE      = newElement()
    }
}

case class UsbHost() extends Component {

    // Everything in this block runs at ULPI 60MHz clock speed.
    // If the APB is running at a different clock speed, use Apb3CC which is a clock crossing
    // APB bridge.

    val io = new Bundle {

        // Interface into RAM that contains all the FIFOs.
        // So instead of a 'real' FIFO, it's just a RAM.
        val cpu_fifo_bus            = slave(PipelinedMemoryBus(UsbHost.getFifoMemoryBusConfig()))

        // Used for transmit and receive operations

        // Peripheral address to be used for next transaction. Static value.
        val periph_addr             = in(UInt(7 bits))
        // Endpoint nr for next transaction. Static value.
        val endpoint                = in(UInt(4 bits))

        // When high, the CPU is allowed to write to the send FIFO. Value changes
        // after a transmit is started.
        val send_buf_avail          = out(Bool)
        // Indicates which one of the double-buffered FIFOs should be used to write a packet to.
        // Value changes after a transmit is started.
        val send_buf_avail_nr       = out(Bool)

        // Number of bytes that were written in the currently available send buffer
        val send_byte_count         = slave(Flow(UInt(6 bits)))

        // Type of transfer that's initiated. Determines the PID as well as
        // the number of global state machine steps
        // Static value.
        val xfer_type               = in(UsbHost.HostXferType)

        // Kick-off of a transfer. Pulse.
        val xfer_start              = in(Bool)

        val xfer_result             = out(UsbHost.HostXferResult)

    }

    // 2x64 deep double-buffered RX FIFOs
    // 2x64 deep double-buffered TX FIFOs
    // 8 deep setup TX FIFO.

    // "000xxxxxx" : RX FIFO 0
    // "001xxxxxx" : RX FIFO 1
    // "010xxxxxx" : TX FIFO 0
    // "011xxxxxx" : TX FIFO 1
    // "100000xxx" : SU FIFO
    val fifo_ram = Mem(Bits(8 bits), 256+8)

    val cpu_ram_access = new Area {
        io.cpu_fifo_bus.cmd.ready := True
    
        io.cpu_fifo_bus.rsp.data := fifo_ram.readWriteSync(
                    enable              = io.cpu_fifo_bus.cmd.valid,
                    write               = io.cpu_fifo_bus.cmd.write,
                    address             = io.cpu_fifo_bus.cmd.address,
                    mask                = B(True),
                    data                = io.cpu_fifo_bus.cmd.data
            )
        io.cpu_fifo_bus.rsp.valid := RegNext(io.cpu_fifo_bus.cmd.valid && !io.cpu_fifo_bus.cmd.write) init(False)
    }


    def driveFrom(busCtrl: BusSlaveFactory, baseAddress: BigInt) = new Area {

        io.cpu_fifo_bus.cmd.valid   := False
        io.cpu_fifo_bus.cmd.write   := False
        io.cpu_fifo_bus.cmd.address := 0
        busCtrl.nonStopWrite(io.cpu_fifo_bus.cmd.data, 0)

        //============================================================
        // SNDFIFO - Send FIFO
        //============================================================
        val send_fifo = new Area {

            val wr_ptr  = Reg(UInt(6 bits)) init(0)
            val wr_addr = U"2'b01" @@ io.send_buf_avail_nr @@ wr_ptr

            busCtrl.onWrite(UsbHost.SNDFIFO_ADDR){
                io.cpu_fifo_bus.cmd.valid   := True
                io.cpu_fifo_bus.cmd.write   := True
                io.cpu_fifo_bus.cmd.address := wr_addr

                wr_ptr := wr_ptr + 1
            }
        }

        //============================================================
        // SNDBC - Send FIFO Byte Count
        //============================================================
        val send_byte_count = new Area {
            // Right now, this register is write only. It should probably be made r/w?
            val send_byte_count = busCtrl.createAndDriveFlow(io.send_byte_count.payload, UsbHost.SNDBC_ADDR, 0)

            io.send_byte_count  << send_byte_count
        }
    }

}


case class UsbHostTop() extends Component
{
    val io = new Bundle {
        val apb         = slave(Apb3(UsbHost.getApb3Config()))
    }

    val u_usb_host = UsbHost()

    val busCtrl = Apb3SlaveFactory(io.apb)
    val apb_regs = u_usb_host.driveFrom(busCtrl, 0x0)

}


case class UsbHostFormalTb() extends Component
{
    val io = new Bundle {
        val clk             = in(Bool)
        val reset_          = in(Bool)
    }


    val domain = new ClockingArea(ClockDomain(io.clk, io.reset_, 
                                                config = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = LOW)))
    {
        val apb = Apb3(UsbHost.getApb3Config())

        val u_usb_host_top = new UsbHostTop()
        u_usb_host_top.io.apb           <> apb

       import spinal.core.GenerationFlags._
       import spinal.core.Formal._
   
       GenerationFlags.formal{
            import pano.lib._

            assume(io.reset_ === !initstate())

            assume(rose(apb.PENABLE)    |-> stable(apb.PSEL))
            assume(rose(apb.PENABLE)    |-> stable(apb.PADDR))
            assume(rose(apb.PENABLE)    |-> stable(apb.PWRITE))
            assume(rose(apb.PENABLE)    |-> stable(apb.PWDATA))

            assume(apb.PREADY           |-> stable(apb.PENABLE))
            assume(apb.PREADY           |-> stable(apb.PSEL))
            assume(apb.PREADY           |-> stable(apb.PADDR))
            assume(apb.PREADY           |-> stable(apb.PWRITE))
            assume(apb.PREADY           |-> stable(apb.PWDATA))

            assume(fell(apb.PENABLE)    |-> apb.PREADY)
            assume(fell(apb.PSEL.orR)   |-> apb.PREADY)

            assume(!stable(apb.PSEL)    |=> (fell(apb.PENABLE) || !apb.PENABLE))
            assume(!stable(apb.PADDR)   |=> (fell(apb.PENABLE) || !apb.PENABLE))
            assume(!stable(apb.PWRITE)  |=> (fell(apb.PENABLE) || !apb.PENABLE))
            assume(!stable(apb.PWDATA)  |=> (fell(apb.PENABLE) || !apb.PENABLE))

            when(!initstate()){
            }
        }
    }.setName("")
}

object UsbHostVerilog{
    def main(args: Array[String]) {

        val config = SpinalConfig(anonymSignalUniqueness = true)
        config.includeFormal.generateSystemVerilog({
            val toplevel = new UsbHostFormalTb()
            toplevel
        })
        println("DONE")
    }
}

