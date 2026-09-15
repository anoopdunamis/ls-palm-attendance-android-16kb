package com.dunamis.world.lspalmattendance.home

interface HomeView {

    fun getAttendanceRes(data: Boolean)
    fun attendanceDownloaded()
    fun attendanceDownloadFailed()

    fun getPalmRes(data: Boolean)
    fun palmDownloaded()
    fun palmDownloadFailed()
}