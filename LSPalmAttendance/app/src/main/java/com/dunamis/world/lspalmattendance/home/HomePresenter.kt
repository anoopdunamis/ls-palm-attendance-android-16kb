package com.dunamis.world.lspalmattendance.home

import android.content.Context
import com.android.volley.AuthFailureError
import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.RetryPolicy
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.dunamis.world.lspalmattendance.db.DatabaseHandler
import com.dunamis.world.lspalmattendance.db.Palm
import com.dunamis.world.lspalmattendance.db.Students
import com.dunamis.world.lspalmattendance.statics.SharedPref
import com.dunamis.world.lspalmattendance.statics.URLS
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class HomePresenter(c: HomeActivity) {

    private var c: Context = c
    private var homeView: HomeView? = c
    private var sharedPref: SharedPref = SharedPref(c)
    private var databaseHandler: DatabaseHandler = DatabaseHandler(c)

    fun getAttendanceDetails(pageNo: String) {

        class GetAttendanceTask : Thread() {
            override fun run() {
                super.run()

                try {
                    val paramsObj = JSONObject()
                    paramsObj.put("page", pageNo)
                    val url: String = sharedPref.getBaseUrl() + URLS().STUDENT_LIST_UPDATED
//                    AppController.getInstance().getRequestQueue().getCache().invalidate(url, true)
                    val jsonObjReq: StringRequest = object : StringRequest(Request.Method.POST,
                        url,
                        Response.Listener { response: String? ->
                            try {
                                val jsonObjectRes = JSONObject(response.toString())
                                if (jsonObjectRes.has("status")) {
                                    if (jsonObjectRes.getString("status").equals("success")) {
                                        if (pageNo == "1") {

                                            databaseHandler.resetStudentsTable()
                                            if (jsonObjectRes.has("student_total_count")) {

                                                sharedPref.setTotalStudent(jsonObjectRes.getString("student_total_count"))
                                            }
                                        }

                                        val tripList: JSONArray =
                                            jsonObjectRes.getJSONArray("student_list")
                                        val alChildDetails = mutableListOf<Students>()
                                        if (tripList.length() > 0) {
                                            for (i in 0 until tripList.length()) {

                                                val obj = tripList.getJSONObject(i)

                                                fun getSafe(key: String,
                                                    default: String = ""): String {
                                                    return if (obj.has(key)) {
                                                        val value = obj.getString(key).trim()
                                                        if (value.equals("null",
                                                                true) || value.isEmpty()) default else value
                                                    } else default
                                                }

                                                val student =
                                                    Students(childId = getSafe("child_id"),
                                                        childName = getSafe("child_name"),
                                                        childClass = getSafe("child_class"),
                                                        childSection = getSafe("child_section"),
                                                        childNfcId = getSafe("child_nfc_id"),
                                                        childRegNo = getSafe("child_reg_no"),
                                                        childFatherName = getSafe("child_father_name"),
                                                        tripNoPickUp = getSafe("child_trip_no_pickup"),
                                                        tripNoDropOff = getSafe("child_trip_no_drop_off"),
                                                        childOrEmp = getSafe("child_or_employee"),
                                                        pcrValidityEndDate = getSafe("child_pcr_validity_end_date"),
                                                        parentNfcId = getSafe("child_parent_nfc_id"),
                                                        parentAcNo = getSafe("parent_account_no"),
                                                        handicappedType = getSafe("child_handicapped_type"),
                                                        suspensionDate = getSafe("quarantine_end_date"),
                                                        motherNfc = getSafe("child_mother_nfc"),
                                                        entryRestriction = getSafe("child_city",
                                                            "IN-OUT"),
                                                        childAuthorizedPickup = getSafe("child_authorized_pickup",
                                                            "no"),
                                                        childGender = getSafe("child_gender"),
                                                        childPhoto = getSafe("child_photo"))
                                                alChildDetails.add(student)
                                            }

                                            val data: Boolean =
                                                databaseHandler.addBatchStudents(alChildDetails)
                                            if (data) {
                                                homeView?.getAttendanceRes(data)
                                            } else {
                                                databaseHandler.resetStudentsTable()
                                                homeView?.getAttendanceRes(data)
                                            }
                                        } else {
                                            homeView?.attendanceDownloaded()
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                homeView?.attendanceDownloadFailed()
                            }
                        },
                        Response.ErrorListener { error -> homeView?.attendanceDownloadFailed() }) {
                        @Throws(AuthFailureError::class)
                        override fun getParams(): Map<String, String> {
                            val map: MutableMap<String, String> = HashMap()
                            map["data"] = paramsObj.toString()
                            return map
                        }
                    }

//                            val headers: MutableMap<String?, String?>
//                                get() = createBasicAuthHeader()
//
//                            fun createBasicAuthHeader(): HashMap<String?, String?> {
//                                val headerMap = HashMap<String?, String?>()
//                                val credentials = ""
//                                val base64EncodedCredentials = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
//                                headerMap.put("Authorization", "Basic " + base64EncodedCredentials)
//                                return headerMap
//                            }
//                        }

                    // RetryPolicy policy = new DefaultRetryPolicy(AppConfig.TIMEOUT, 4, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT);
                    val policy: RetryPolicy = DefaultRetryPolicy(8000,
                        DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                        DefaultRetryPolicy.DEFAULT_BACKOFF_MULT)
                    jsonObjReq.setRetryPolicy(policy)
                    jsonObjReq.setShouldCache(false)
                    val requestQueue = Volley.newRequestQueue(c)
                    requestQueue.add(jsonObjReq)
//                    AppController.getInstance().getRequestQueue().getCache().remove(url)
                } catch (e: JSONException) {

                    homeView?.attendanceDownloadFailed()
                }
            }
        }
        GetAttendanceTask().start()
    }

    fun getPalmDetails(palmPageNo: String) {

        class GetPalmTask : Thread() {
            override fun run() {
                super.run()

                try {
                    val paramsObj = JSONObject()
                    paramsObj.put("page", palmPageNo)
                    paramsObj.put("last_updated_time",
                        sharedPref.getPalmDBDownloadedTimeGMT() ?: "")
                    val url: String = sharedPref.getBaseUrl() + URLS().GET_PALM_BATCH
                    val jsonObjReq: StringRequest = object : StringRequest(Method.POST,
                        url,
                        Response.Listener { response: String? ->
                            try {
                                val jsonObjectRes = JSONObject(response ?: "")
                                if (jsonObjectRes.optString("status")
                                        .equals("success", ignoreCase = true)) {

                                    if (palmPageNo == "1") {
                                        if (sharedPref.getPalmDBDownloadedTimeGMT()
                                                .isNullOrEmpty()) {
                                            databaseHandler.resetPalmTable()
                                        }
                                        if (jsonObjectRes.has("total_count")) {
                                            sharedPref.setTotalPalm(jsonObjectRes.optString("total_count")
                                                .trim())
                                        }
                                    }

                                    val jsonArray = jsonObjectRes.optJSONArray("palm_list")
                                    if (jsonArray != null && jsonArray.length() > 0) {
                                        val alPalmDetails = mutableListOf<Palm>()
                                        val childIdsToDelete = mutableListOf<String?>()
                                        val isDbDownloaded =
                                            !sharedPref.getPalmDBDownloadedTimeGMT().isNullOrEmpty()

                                        val optStringOrNull = { obj: JSONObject, key: String ->
                                            if (obj.isNull(key)) null
                                            else {
                                                val s = obj.optString(key)
                                                if (s == "null" || s.isEmpty()) null else s
                                            }
                                        }

                                        val jsonArrayToByteArray = { jsonStr: String? ->
                                            if (jsonStr == null || jsonStr == "null" || jsonStr == "[]" || jsonStr.isEmpty()) {
                                                null
                                            } else {
                                                try {
                                                    val arr = JSONArray(jsonStr)
                                                    ByteArray(arr.length()) { k ->
                                                        arr.getInt(k).toByte()
                                                    }
                                                } catch (e: Exception) {
                                                    null
                                                }
                                            }
                                        }

                                        for (i in 0 until jsonArray.length()) {
                                            val obj = jsonArray.getJSONObject(i)
                                            val palmChildId = optStringOrNull(obj, "palm_chilld_id")

                                            val palmDataRgbLeft = jsonArrayToByteArray(obj.optString("palm_data_rgb_left"))
                                            val palmDataIrLeft = jsonArrayToByteArray(obj.optString("palm_data_ir_left"))
                                            val palmDataRgbRight = jsonArrayToByteArray(obj.optString("palm_data_rgb_right"))
                                            val palmDataIrRight = jsonArrayToByteArray(obj.optString("palm_data_ir_right"))

                                            if (palmDataRgbLeft != null || palmDataIrLeft != null || palmDataRgbRight != null || palmDataIrRight != null) {
                                                if (isDbDownloaded && palmChildId != null) {
                                                    childIdsToDelete.add(palmChildId)
                                                }

                                                alPalmDetails.add(
                                                    Palm(
                                                        palmId = optStringOrNull(obj, "palm_id"),
                                                        palmDataOne = optStringOrNull(obj, "palm_data_1"),
                                                        childId = palmChildId,
                                                        childRegNo = optStringOrNull(obj, "palm_reg_no"),
                                                        palmDataRgbLeft = palmDataRgbLeft,
                                                        palmDataIrLeft = palmDataIrLeft,
                                                        palmDataRgbRight = palmDataRgbRight,
                                                        palmDataIrRight = palmDataIrRight
                                                    )
                                                )
                                            }
                                        }

                                        if (childIdsToDelete.isNotEmpty()) {
                                            databaseHandler.deletePalmsByChildIds(childIdsToDelete)
                                        }

                                        val data = databaseHandler.addBatchPalm(alPalmDetails)
                                        if (data) {
                                            homeView?.getPalmRes(data)
                                        } else {
                                            databaseHandler.resetPalmTable()
                                            homeView?.getPalmRes(data)
                                        }
                                    } else {
                                        homeView?.palmDownloaded()
                                    }
                                } else {
                                    homeView?.palmDownloaded()
                                }
                            } catch (e: Exception) {
                                homeView?.palmDownloadFailed()
                            }
                        },
                        Response.ErrorListener { homeView?.palmDownloadFailed() }) {
                        @Throws(AuthFailureError::class)
                        override fun getParams(): Map<String, String> {
                            val map: MutableMap<String, String> = HashMap()
                            map["data"] = paramsObj.toString()
                            return map
                        }
                    }
                    val policy: RetryPolicy = DefaultRetryPolicy(8000,
                        DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                        DefaultRetryPolicy.DEFAULT_BACKOFF_MULT)
                    jsonObjReq.retryPolicy = policy
                    jsonObjReq.setShouldCache(false)
                    val requestQueue = Volley.newRequestQueue(c)
                    requestQueue.add(jsonObjReq)
                } catch (e: JSONException) {
                    homeView?.palmDownloadFailed()
                }
            }
        }
        GetPalmTask().start()
    }
}