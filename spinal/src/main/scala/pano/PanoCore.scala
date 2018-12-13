
package pano

import spinal.core._
import spinal.lib._
import spinal.lib.io._

import mr1._

class PanoCore extends Component {

    val io = new Bundle {
        val led_red             = out(Bool)
        val led_green           = out(Bool)
        val led_blue            = out(Bool)

        val switch_             = in(Bool)

        val dvi_ctrl_scl        = master(TriState(Bool))
        val dvi_ctrl_sda        = master(TriState(Bool))

        val vo                  = out(VgaData())
    }

    val leds = new Area {
        val led_cntr = Reg(UInt(24 bits)) init(0)

        when(led_cntr === U(led_cntr.range -> true)){
            led_cntr := 0
        }
        .otherwise {
            led_cntr := led_cntr +1
        }

        io.led_green    := led_cntr.msb
    }

    val test_pattern_nr = UInt(4 bits)
    val const_color     = Pixel()

    val mr1Config = MR1Config()
    val u_mr1_top = new MR1Top(mr1Config)
    u_mr1_top.io.led1       <> io.led_red
    u_mr1_top.io.led2       <> io.led_blue
    u_mr1_top.io.switch_    <> io.switch_
    u_mr1_top.io.dvi_ctrl_scl    <> io.dvi_ctrl_scl
    u_mr1_top.io.dvi_ctrl_sda    <> io.dvi_ctrl_sda
    u_mr1_top.io.test_pattern_nr            <> test_pattern_nr
    u_mr1_top.io.test_pattern_const_color   <> const_color

    val timings = VideoTimings()
    timings.h_active        := 640
    timings.h_fp            := 16
    timings.h_sync          := 96
    timings.h_bp            := 48
    timings.h_sync_positive := False
    timings.h_total_m1      := (timings.h_active + timings.h_fp + timings.h_sync + timings.h_bp -1).resize(timings.h_total_m1.getWidth)

    timings.v_active        := 480
    timings.v_fp            := 11
    timings.v_sync          := 2
    timings.v_bp            := 31
    timings.v_sync_positive := False
    timings.v_total_m1      := (timings.v_active + timings.v_fp + timings.v_sync + timings.v_bp -1).resize(timings.v_total_m1.getWidth)

    val vi_gen_pixel_out = PixelStream()

    val u_vi_gen = new VideoTimingGen()
    u_vi_gen.io.timings         <> timings
    u_vi_gen.io.pixel_out       <> vi_gen_pixel_out

    val test_patt_pixel_out = PixelStream()

    val u_test_patt = new VideoTestPattern()
    u_test_patt.io.timings      <> timings
    u_test_patt.io.pixel_in     <> vi_gen_pixel_out
    u_test_patt.io.pixel_out    <> test_patt_pixel_out
    u_test_patt.io.pattern_nr   <> test_pattern_nr
    u_test_patt.io.const_color  <> const_color

    val u_vo = new VideoOut()
    u_vo.io.timings             <> timings
    u_vo.io.pixel_in            <> test_patt_pixel_out
//    u_vo.io.pixel_in            <> vi_gen_pixel_out
    u_vo.io.vga_out             <> io.vo

}

