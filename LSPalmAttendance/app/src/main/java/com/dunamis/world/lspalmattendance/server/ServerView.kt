package com.dunamis.world.lspalmattendance.server

interface ServerView {

    fun success(
        alKeysList: ArrayList<String>,
        alValueList: ArrayList<String>,
        alImageList: ArrayList<String>
    )

    fun failed()
}