package com.dunamis.world.lspalmattendance.recog

interface RecognitionView {

    fun attendanceSuccess()
    fun attendanceFailed()
    fun palmDetected()
}