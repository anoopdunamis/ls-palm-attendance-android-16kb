package com.dunamis.world.lspalmattendance.recog

import android.content.Context
import com.android.volley.AuthFailureError
import com.android.volley.Request
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.dunamis.world.lspalmattendance.db.Attendance
import com.dunamis.world.lspalmattendance.db.DatabaseHandler
import com.dunamis.world.lspalmattendance.statics.SharedPref
import com.dunamis.world.lspalmattendance.statics.URLS
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class RecognitionPresenter(c: RecognitionActivity) {

    private var c: Context = c
    private var recognitionView: RecognitionView? = c
    private var sharedPref: SharedPref = SharedPref(c)
    private var databaseHandler = DatabaseHandler(c)

    fun markAttendance() {

        class GetAttendanceTask : Thread() {
            override fun run() {
                super.run()

                val jArrayObject = JSONArray()
                val attendanceDetails: MutableList<Attendance> =
                    databaseHandler.getAllAttendanceDetails() as MutableList<Attendance>
                val params: MutableMap<String?, String?> = HashMap<String?, String?>()
                var jsonObject: JSONObject? = null

                var lastLocalId = -1
                for (attendance in attendanceDetails) {
                    lastLocalId = attendance.id
                    jsonObject = JSONObject()
                    try {
                        jsonObject.put("at_loc_longi", attendance.longitude)
                        jsonObject.put("at_loc_lati", attendance.latitude)
                        jsonObject.put("at_device_id", attendance.busId)
                        jsonObject.put("at_time_stamp_device", attendance.timeStamp)
                        jsonObject.put("at_loc_data_type", attendance.dataType)
                        jsonObject.put("at_child_id", attendance.childId)
                        jsonObject.put("at_in_or_out", attendance.pickUpDropOff)
                        jsonObject.put("at_child_status", attendance.childStatus)
                        jsonObject.put("child_nfc", attendance.childNfcId)
                        jsonObject.put("child_forgot_card", attendance.childForgotCard)
                        jsonObject.put("child_reg_no", attendance.childRegNo)
                        jsonObject.put("at_child_trip_no_pickup", attendance.tripNoPickUp)
                        jsonObject.put("at_child_trip_no_drop_off", attendance.tripNoDropOff)
                        jsonObject.put("child_name", attendance.childName)
                        jsonObject.put("child_or_employee", attendance.childOrEmp)
                        jsonObject.put("authorized_person_id", attendance.childAuthId)
                        jsonObject.put("at_child_grade", attendance.childClassAuth)
                        jsonObject.put("at_child_section", attendance.childSectionAuth)
                    } catch (e: JSONException) {
                    }
                    jArrayObject.put(jsonObject)
                }
//                params.put("data", jArrayObject.toString())
//                requestQueue.cancelAll("markattendance")
//                AppController.getInstance().getRequestQueue().getCache().invalidate(sharedPref.getBaseUrl() + URLS().MARK_ATTENDANCE_BATCH, true)
                val jsonObjReq: StringRequest = object : StringRequest(Request.Method.POST,
                    sharedPref.getBaseUrl() + URLS().MARK_ATTENDANCE_BATCH,
                    { response ->
                        try {
                            val jsbObjectRes = JSONObject(response.toString())
                            if (jsbObjectRes.has("status")) {
                                if (jsbObjectRes.getString("status")
                                        .equals("success", ignoreCase = true)) {

                                    databaseHandler.deleteAttendanceUpToId(lastLocalId)
                                    recognitionView?.attendanceSuccess()
                                }
                            }
                        } catch (e: Exception) {
                            recognitionView?.attendanceFailed()
                        }
                    },
                    { error -> recognitionView?.attendanceFailed() }) {

                    @Throws(AuthFailureError::class)
                    override fun getParams(): Map<String, String> {
                        val map: MutableMap<String, String> = HashMap()
                        map["data"] = jArrayObject.toString()
                        return map
                    }
//                    val headers: MutableMap<String?, String?>
//                        get() = createBasicAuthHeader()
//
//                    fun createBasicAuthHeader(): HashMap<String?, String?> {
//                        val headerMap = HashMap<String?, String?>()
//                        val credentials = ""
//                        val base64EncodedCredentials = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
//                        headerMap.put("Authorization", "Basic " + base64EncodedCredentials)
//                        return headerMap
//                    }
                }

//                jsonObjReq.setTag("markattendance")
//                val policy: RetryPolicy = DefaultRetryPolicy(AppConfig.TIMEOUT_EXTENDED, 0, 0f)
//                jsonObjReq.setRetryPolicy(policy)
//                jsonObjReq.setShouldCache(false)
                val requestQueue = Volley.newRequestQueue(c)
                requestQueue.add(jsonObjReq)
//                AppController.getInstance().getRequestQueue().getCache().remove(sharedPref.getBaseUrl() + URLS().MARK_ATTENDANCE_BATCH)
            }
        }
        GetAttendanceTask().start()
    }
}