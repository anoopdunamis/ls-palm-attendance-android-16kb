package com.dunamis.world.lspalmattendance.recog

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.api.stream.Device
import com.api.stream.Device.DeviceListener
import com.api.stream.Frame
import com.api.stream.ICapturePalmCallback
import com.api.stream.IDevice
import com.api.stream.IOpenCallback
import com.api.stream.StreamType
import com.api.stream.bean.BBox
import com.api.stream.bean.CaptureFrame
import com.api.stream.bean.ExtraFrameInfo
import com.api.stream.bean.ImageInstance
import com.api.stream.enumclass.Hint
import com.api.stream.enumclass.RecognizeMode
import com.api.stream.manager.DtUsbDevice
import com.api.stream.manager.DtUsbManager.DeviceStateListener
import com.api.stream.manager.UsbMapTable
import com.api.stream.veinshine.IVeinshine
import com.dunamis.world.lspalmattendance.R
import com.dunamis.world.lspalmattendance.custom.DtRectRoiView
import com.dunamis.world.lspalmattendance.databinding.ActivityRecognitionBinding
import com.dunamis.world.lspalmattendance.databinding.BackDialogBinding
import com.dunamis.world.lspalmattendance.db.Attendance
import com.dunamis.world.lspalmattendance.db.DatabaseHandler
import com.dunamis.world.lspalmattendance.db.Palm
import com.dunamis.world.lspalmattendance.home.HomeActivity
import com.dunamis.world.lspalmattendance.statics.KotlinStatic
import com.dunamis.world.lspalmattendance.statics.SharedPref
import com.dunamis.world.lspalmattendance.util.FileUtils
import com.dunamis.world.lspalmattendance.util.IOUtils
import com.dunamis.world.lspalmattendance.util.ResourceUtils
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.palm.common.opengl.GLDisplay
import com.palm.common.opengl.GLFrameSurface
import com.squareup.picasso.MemoryPolicy
import com.squareup.picasso.NetworkPolicy
import com.squareup.picasso.Picasso
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

open class RecognitionActivity : AppCompatActivity(), RecognitionView {

    private val TAG = "RecognitionActivity"
    private var mGLIrView: GLFrameSurface? = null
    private var mGLRgbView: GLFrameSurface? = null

    private var rgbDisPlay: GLDisplay? = null
    private var irDisPlay: GLDisplay? = null

    private var mBtnOpen: Button? = null
    private var mTvDeviceInfo: TextView? = null
    private var mSwitchStartStream: Switch? = null
    private var mSpinnerStreamMode: Spinner? = null

    private val deviceThread: ExecutorService = Executors.newSingleThreadExecutor()
    private val matchPool: ExecutorService =
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())

    @Volatile
    private var mDevice: IDevice? = null
    private var mainHandler: Handler? = null
    private var mAdapterStreamType: ArrayAdapter<StreamType>? = null
    private val mListStreamType: MutableList<StreamType> = ArrayList()
    private var currentStreamType: StreamType = StreamType.INVALID_STREAM_TYPE

    @Volatile
    private var mIsRunning = false
    private var mStreamThread: Thread? = null

    @Volatile
    private var mIsOpenCamera = false
    private var rgbFrameData1: ByteArray? = null
    private var irFrameData1: ByteArray? = null
    private var irFrameExtraInfo: ExtraFrameInfo? = null
    private var irFrameW1 = 0
    private var irFrameH1 = 0
    private var rgbFrameW1 = 0
    private var rgbFrameH1 = 0

    protected var mRgbBitmap: Bitmap? = null
    protected var mIrBitmap: Bitmap? = null

    @Volatile
    private var algoStatus: EnableAlgorithmStatus = EnableAlgorithmStatus.DISABLE

    enum class EnableAlgorithmStatus { DISABLE, ENABLE, INITIALIZING }

    companion object {
        private const val TAG_ENGINE = "PalmEngine"
        private val REQUEST_PERMISSION = arrayOf(
            PermissionLists.getCameraPermission()
        )
    }

    private val dir: String by lazy {
        getExternalFilesDir(null)?.absolutePath + File.separator + "HeyStar"
    }

    private var mPalmCache: MutableList<Palm> = ArrayList()

    enum class WorkMode { NONE, REGISTER, RECOGNIZE }

    private val mode = RecognizeMode.kBiModal

    @Volatile
    private var mCurrentWorkMode = WorkMode.NONE

    private var regLeftRgb: ByteArray? = null
    private var regLeftIr: ByteArray? = null
    private var regRightRgb: ByteArray? = null
    private var regRightIr: ByteArray? = null

    enum class RegHand { LEFT, RIGHT, NONE }

    private var mRegHandMode = RegHand.NONE

    private var PALM_DELAY_MILLIS: Long = 2000
    private lateinit var kotlinStatic: KotlinStatic

    private var colorSelected = 0
    private lateinit var c: Context
    private lateinit var sharedPref: SharedPref
    private lateinit var databaseHandler: DatabaseHandler

    private lateinit var homeBinding: ActivityRecognitionBinding

    private val markAttendanceRunnable = object : Runnable {
        override fun run() {

            if (databaseHandler.getAttendanceCount() > 0) {

                RecognitionPresenter(this@RecognitionActivity).markAttendance()
            }
            mainHandler?.postDelayed(this, 10000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        homeBinding = ActivityRecognitionBinding.inflate(layoutInflater)
        setContentView(homeBinding.root)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.clRecognition)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        c = this
        kotlinStatic = KotlinStatic(c)
        homeBinding.tvVersion.text = kotlinStatic.getVersionNo()
        sharedPref = SharedPref(c)
        databaseHandler = DatabaseHandler(c)

        if (sharedPref.getEntryType()?.trim().equals("in", ignoreCase = true)) {

            homeBinding.tvEntryOrExit.text = resources.getString(R.string.entry)
            homeBinding.tvEntryOrExit.setTextColor(resources.getColor(R.color.green))
        } else {

            homeBinding.tvEntryOrExit.text = resources.getString(R.string.exit)
            homeBinding.tvEntryOrExit.setTextColor(resources.getColor(R.color.red))
        }
        homeBinding.tvDBCount.text =
            "DB Total: ${databaseHandler.getStudentCount()} | Palm Total: ${databaseHandler.getPalmCount()}"
        homeBinding.tvDBUpdatedDate.text = sharedPref.getStudentDBDownloadedTime()
        setPendingAttendanceCount()

        loadLogoImg()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showBackDialog()
            }
        })

        homeBinding.btnBack.setOnClickListener {

            showBackDialog()
        }

        loadPalmCache()
        checkPermission()
        initView()

        rgbDisPlay = GLDisplay()
        irDisPlay = GLDisplay()
        mainHandler = Handler(Looper.getMainLooper())

        // Proactively attempt to open the device when starting the activity
        openDevice()
    }

    override fun onResume() {
        super.onResume()

        if (!sharedPref.getRecognitionPageTime()
                .equals(kotlinStatic.fetchDate(), ignoreCase = true)) {

            gotoHome()
        }
        mainHandler?.post(markAttendanceRunnable)
    }

    override fun onPause() {
        super.onPause()
        mainHandler?.removeCallbacks(markAttendanceRunnable)
    }

    private fun gotoHome() {

        sharedPref.setEntryType("")
        sharedPref.setRecognitionPageTime("")
        startActivity(Intent(c, HomeActivity::class.java))
        finishAffinity()
    }

    private fun loadPalmCache() {
        matchPool.execute {
            mPalmCache = databaseHandler.getPalms() as MutableList<Palm>
        }
    }

    private fun checkPermission() {
        XXPermissions.with(this)
            .permissions(REQUEST_PERMISSION)
            .request { granted, denied ->
                if (denied.isEmpty()) {
                    IOUtils.createFolder(dir)
                    IOUtils.createFolder(dir + File.separator + "models")
                    copyAssetsFile()
                } else {
                    if (XXPermissions.isDoNotAskAgainPermissions(this@RecognitionActivity,
                            denied)) {
                        XXPermissions.startPermissionActivity(this@RecognitionActivity, denied)
                    } else {
                        showToast("Permissions required")
                        finish()
                    }
                }
            }
    }

    private fun initView() {

        // Access views correctly from bindings
        mGLRgbView = homeBinding.surfaceView.mGLRgbView
        mGLIrView = homeBinding.surfaceView.mGLIrView

        mGLIrView?.post {
            mGLRgbView?.setDisplay(mGLRgbView!!.width, mGLRgbView!!.width * 1024 / 720)
            mGLIrView?.setDisplay(mGLIrView!!.width, mGLIrView!!.width * 1024 / 720)
        }

        mAdapterStreamType = ArrayAdapter(c,
            android.R.layout.simple_spinner_item,
            mListStreamType) as ArrayAdapter<StreamType>?

        mBtnOpen = homeBinding.openViewGroup.mBtnOpen
        mTvDeviceInfo = homeBinding.openViewGroup.mTvDeviceInfo
        mSwitchStartStream = homeBinding.openViewGroup.mSwitchStartStream
        mSpinnerStreamMode = homeBinding.openViewGroup.mSpinnerStreamMode

        mSpinnerStreamMode?.adapter = mAdapterStreamType
        initListener()
    }

    private fun copyAssetsFile() {
        val model: String = dir + File.separator + "models/palm_models_1.3.5.bin"
        if (FileUtils.isFileExists(model)) return

//        showProgressDialog("Initializing models...")
        Executors.newSingleThreadExecutor().execute {
            try {
                ResourceUtils.copyFileFromAssets("models", dir + File.separator + "models")
            } catch (e: Exception) {
            }
//            runOnUiThread(Runnable { this.dismissProgressDialog() })
        }
    }

    private fun initListener() {

        homeBinding.llImgBG.setOnClickListener { openDevice() }
        homeBinding.llDeviceConnect.setOnClickListener { openDevice() }
        mBtnOpen?.setOnClickListener { openDevice() }
        mSwitchStartStream?.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            if (isChecked) {
                if (!mIsOpenCamera) {
//                    showToast("Connect sensor first")
                    mSwitchStartStream?.isChecked = false
                    return@setOnCheckedChangeListener
                }
                if (mSpinnerStreamMode != null) mSpinnerStreamMode?.isEnabled = false
                startStream()
            } else {
                if (mSpinnerStreamMode != null) mSpinnerStreamMode?.isEnabled = true
                mIsRunning = false
                mainHandler?.postDelayed({ this.clearFrame() }, 200)
            }
        }
        mSpinnerStreamMode?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentStreamType = mListStreamType[pos]
            }

            override fun onNothingSelected(p: AdapterView<*>?) {
            }
        }
        homeBinding.mBtnEnable.setOnClickListener { enableDimPalm() }
        homeBinding.mBtnRegister.setOnClickListener {
            if (checkReady()) {
//                showRegisterMainDialog()
            }
        }
        homeBinding.mBtnRecognize.setOnClickListener {
            if (checkReady()) {
                mCurrentWorkMode = WorkMode.RECOGNIZE
//                showToast("VERIFYING...")
                mainHandler?.post {

                    homeBinding.ivStudent.visibility = View.GONE
                    homeBinding.lavAnimInit.visibility = View.GONE
                    homeBinding.llLavBorder.visibility = View.VISIBLE
//                    homeBinding.lavAnimRecog.visibility = View.VISIBLE
                    homeBinding.lavAnimInit.cancelAnimation()
                    homeBinding.lavAnimRecog.playAnimation()

                    colorSelected = 1
                    homeBinding.llLavBorder.setBackgroundResource(R.drawable.bg_dodger_blue_55)
                    homeBinding.llImgBG.setBackgroundResource(R.drawable.bg_black_55)
//                    homeBinding.llImgBG.setBackgroundResource(R.drawable.bg_plam_not_recognized_55)

                    homeBinding.tvName.text = resources.getString(R.string.scanning_palm)
                    homeBinding.tvClassSec.text = resources.getString(R.string.scanning_palm_info)

                    homeBinding.lavAnimSuccess.visibility = View.GONE
                    homeBinding.lavAnimFail.visibility = View.GONE
                }
                captureOnce()
            }
        }
        homeBinding.mBtnStopCapture.setOnClickListener { stopCapture() }
    }

    private fun checkReady(): Boolean {
        if (!mIsOpenCamera) {
//            showToast("Open sensor first")
            return false
        }
        if (algoStatus != EnableAlgorithmStatus.ENABLE) {
//            showToast("Initialize engine first")
            return false
        }
        return true
    }

    private fun enableDimPalm() {

        if (algoStatus == EnableAlgorithmStatus.ENABLE) return
        val modelPath = dir + File.separator + "models" + File.separator
        matchPool.execute {
            if (mDevice != null) {
                algoStatus = EnableAlgorithmStatus.INITIALIZING
                if ((mDevice as IVeinshine).enableDimPalm(modelPath, mode) == 0) {
                    algoStatus = EnableAlgorithmStatus.ENABLE
//                    showToast("Algorithm Ready")

                    //new
                    mainHandler?.post {

                        if (mSwitchStartStream != null) mSwitchStartStream?.isChecked = true

                        mainHandler?.postDelayed({
                            homeBinding.mBtnRecognize.performClick()
                        }, 1000)
                    }
                    //new
                } else {
                    algoStatus = EnableAlgorithmStatus.DISABLE
//                    showToast("Init Failed")
                }
            }
        }
//        AlertDialog.Builder(this).setTitle("Biometric Setup").setView(input)
//            .setPositiveButton("Init"
//            ) { d: DialogInterface?, w: Int ->
//                matchPool.execute {
//                    if (mDevice != null) {
//                        algoStatus = EnableAlgorithmStatus.INITIALIZING
//                        if ((mDevice as IVeinshine).enableDimPalm(input.text
//                                .toString()) == 0) {
//                            algoStatus = EnableAlgorithmStatus.ENABLE
//                            showToast("Algorithm Ready")
//                        } else {
//                            algoStatus = EnableAlgorithmStatus.DISABLE
//                            showToast("Init Failed")
//                        }
//                    }
//                }
//            }.show()
    }

    private val recognizeRunnable = Runnable {
        if (!isFinishing && !isDestroyed && algoStatus == EnableAlgorithmStatus.ENABLE) {
            homeBinding.mBtnRecognize.performClick()
        }
    }

    private fun restartRecognitionWithDelay(delay: Long) {
        mainHandler?.removeCallbacks(recognizeRunnable)
        mainHandler?.postDelayed(recognizeRunnable, delay)
    }

    private fun performRecognition(frame: CaptureFrame) {

        val device = mDevice as? IVeinshine
        if (device == null || mPalmCache.isEmpty()) {
            if (mPalmCache.isEmpty()) showToast("Database empty")
            restartRecognitionWithDelay(PALM_DELAY_MILLIS)
            return
        }
        matchPool.execute {
            val rgbIns = if (frame.rgbData != null) ImageInstance(frame.rgbCols,
                frame.rgbRows,
                frame.rgbData,
                ImageInstance.ImageFormat.IMG_3C8BIT) else null
            val irIns = ImageInstance(frame.irCols,
                frame.irRows,
                frame.irData,
                ImageInstance.ImageFormat.IMG_1C8BIT)

            val live = device.extractPalmFeaturesFromImg(rgbIns, irIns)
            if (live == null || live.result != 0) {
                showToast("Capture quality low. Keep steady.")
                restartRecognitionWithDelay(PALM_DELAY_MILLIS)
                return@execute
            }

            var bestMatchCandidate: Palm? = null
            var maxScore = 0f
            val liveHandType = live.palmType // 0 for Left, 1 for Right

            for (candidate in mPalmCache) {
                val candidateRgb: ByteArray? =
                    if (liveHandType == 0) candidate.palmDataRgbLeft else candidate.palmDataRgbRight
                val candidateIr: ByteArray? =
                    if (liveHandType == 0) candidate.palmDataIrLeft else candidate.palmDataIrRight

                if (candidateIr == null || candidateIr.isEmpty()) continue

                val res = device.compareFeatureScore(candidateRgb,
                    candidateIr,
                    live.rgbFeature,
                    live.irFeature, mode)
                if (res.irScore > 0.75 && res.irScore > maxScore) {
                    maxScore = res.irScore
                    bestMatchCandidate = candidate
                }
            }

            if (kotlinStatic.isAutoTimeEnabled()) {

                if (bestMatchCandidate != null) {
                    val studentMatched =
                        databaseHandler.getStudentByRegNo(bestMatchCandidate.childRegNo)
                    if (studentMatched != null) {
//                    showToast("MATCH FOUND: " + studentMatched.childName)

                        val attendance = Attendance(
                            id = 0,
                            latitude = "0.0",
                            longitude = "0.0",
                            dataType = "p",
                            busId = sharedPref.getBusId() ?: "",
                            timeStamp = kotlinStatic.gmtTimeRightNow(),
                            childId = studentMatched.childId,
                            pickUpDropOff = sharedPref.getEntryType(),
                            childStatus = "1",
                            childNfcId = studentMatched.childNfcId,
                            childForgotCard = "0",
                            childRegNo = studentMatched.childRegNo,
                            tripNoPickUp = studentMatched.tripNoPickUp,
                            tripNoDropOff = studentMatched.tripNoDropOff,
                            childName = studentMatched.childName,
                            childOrEmp = studentMatched.childOrEmp,
                            childAuthId = "0",
                            childClassAuth = studentMatched.childClass,
                            childSectionAuth = studentMatched.childSection
                        )

                        val list = ArrayList<Attendance>()
                        list.add(attendance)
                        databaseHandler.addBatchAttendance(list)

                        mainHandler?.post {
                            if (isFinishing || isDestroyed) return@post
                            setPendingAttendanceCount()
                            homeBinding.tvName.text = studentMatched.childName
                            homeBinding.tvClassSec.text =
                                "${studentMatched.childClass} - ${studentMatched.childSection} (ID# ${studentMatched.childRegNo})"

                            homeBinding.lavAnimSuccess.visibility = View.VISIBLE
                            homeBinding.lavAnimSuccess.playAnimation()
                            homeBinding.lavAnimFail.visibility = View.GONE

                            homeBinding.ivStudent.visibility = View.VISIBLE
                            homeBinding.lavAnimInit.visibility = View.GONE
                            homeBinding.llLavBorder.visibility = View.GONE
//                        homeBinding.lavAnimRecog.visibility = View.GONE
                            homeBinding.lavAnimInit.cancelAnimation()
                            homeBinding.lavAnimRecog.cancelAnimation()

                            if (studentMatched.childGender.equals("Female", ignoreCase = true)) {

                                colorSelected = 4
                                homeBinding.llImgBG.setBackgroundResource(R.drawable.bg_pink_female_55)
                                Picasso.get()
                                    .load(sharedPref.getImgUrl() + studentMatched.childPhoto)
                                    .networkPolicy(NetworkPolicy.NO_CACHE)
                                    .memoryPolicy(MemoryPolicy.NO_CACHE)
                                    .placeholder(R.drawable.girl)
                                    .error(R.drawable.girl)
                                    .fit()
                                    .centerCrop()
                                    .into(homeBinding.ivStudent)
                            } else {

                                colorSelected = 3
                                homeBinding.llImgBG.setBackgroundResource(R.drawable.bg_blue_male_55)
                                Picasso.get()
                                    .load(sharedPref.getImgUrl() + studentMatched.childPhoto)
                                    .networkPolicy(NetworkPolicy.NO_CACHE)
                                    .memoryPolicy(MemoryPolicy.NO_CACHE)
                                    .placeholder(R.drawable.boy)
                                    .error(R.drawable.boy)
                                    .fit()
                                    .centerCrop()
                                    .into(homeBinding.ivStudent)
                            }
                        }
                        kotlinStatic.playPalmSuccess()
                    } else {

//                    showToast(resources.getString(R.string.not_recognized))
                        mainHandler?.post {
                            if (isFinishing || isDestroyed) return@post
                            setNameImageUnknown()
                        }
                        kotlinStatic.playPalmFailure()
                    }
                } else {

//                showToast(resources.getString(R.string.not_recognized))
                    mainHandler?.post {
                        if (isFinishing || isDestroyed) return@post
                        setNameImageUnknown()
                    }
                    kotlinStatic.playPalmFailure()
                }
            } else {

                mainHandler?.post {
                    if (isFinishing || isDestroyed) return@post
                    kotlinStatic.showAutoTimeZoneDialog()
                }
            }

            clearFrame()
            restartRecognitionWithDelay(PALM_DELAY_MILLIS)
        }
    }

    private fun setNameImageUnknown() {
        if (isFinishing || isDestroyed) return
        colorSelected = 0
        homeBinding.llImgBG.setBackgroundResource(R.drawable.bg_plam_not_recognized_55)
        homeBinding.ivStudent.setImageResource(R.drawable.question_mark)
        homeBinding.tvName.text = resources.getString(R.string.not_recognized)
        homeBinding.tvClassSec.text = ""

        homeBinding.lavAnimSuccess.visibility = View.GONE
        homeBinding.lavAnimFail.visibility = View.VISIBLE
        homeBinding.lavAnimFail.playAnimation()

        homeBinding.ivStudent.visibility = View.VISIBLE
        homeBinding.lavAnimInit.visibility = View.GONE
        homeBinding.llLavBorder.visibility = View.GONE
//        homeBinding.lavAnimRecog.visibility = View.GONE
        homeBinding.lavAnimInit.cancelAnimation()
        homeBinding.lavAnimRecog.cancelAnimation()
    }

    @Volatile
    private var isPalmDetected = false

    private val mCapturePalmCallback: ICapturePalmCallback = object : ICapturePalmCallback {
        override fun onCaptureFrame(frame: CaptureFrame?) {
            isPalmDetected = false
            if (frame == null) {
                restartRecognitionWithDelay(PALM_DELAY_MILLIS)
                return
            }
            mainHandler?.post {
                if (isFinishing || isDestroyed) return@post
                if (frame.rgbData != null) buildRgbBitmap(frame.rgbData,
                    frame.rgbCols,
                    frame.rgbRows)
                if (frame.irData != null) buildIrBitmap(frame.irData, frame.irCols, frame.irRows)
                if (mRgbBitmap != null) homeBinding.rgbImage.setImageBitmap(mRgbBitmap)
                if (mIrBitmap != null) homeBinding.irImage.setImageBitmap(mIrBitmap)
            }

            if (mCurrentWorkMode == WorkMode.REGISTER) {
                mCurrentWorkMode = WorkMode.NONE
//                startRegisterFlow(frame)
            } else if (mCurrentWorkMode == WorkMode.RECOGNIZE) {
                mCurrentWorkMode = WorkMode.NONE
                performRecognition(frame)
//                stopCapture()
                clearFrame()
            }
        }

        override fun onCapturePalmHint(hint: Hint?, map: HashMap<Int?, Float?>?) {

            if (hint != null && hint != Hint.NO_PALM_DETECTED && hint != Hint.TIMEOUT) {
                if (!isPalmDetected) {

                    isPalmDetected = true
                    palmDetected()
                }
            }

            if (hint == Hint.NO_PALM_DETECTED) {

                isPalmDetected = false
            }

            if (hint == Hint.TIMEOUT) {

                isPalmDetected = false
                mCurrentWorkMode = WorkMode.NONE
                restartRecognitionWithDelay(PALM_DELAY_MILLIS)
            }
        }

        override fun onCapturePalmQualityPass() {

        }
    }

    private fun openDevice() {
        if (mIsOpenCamera) return
        Device.create(this, object : DeviceListener {
            override fun onDeviceCreatedSuccess(d: IDevice,
                i: Int,
                r: MutableMap<Long?, IDevice?>?,
                t: UsbMapTable.DeviceType?) {
                deviceThread.execute { open(d) }
            }

            override fun onDeviceCreateFailed(d: IDevice?) {
            }

            override fun onDeviceDestroy(d: IDevice?) {
                mIsRunning = false
                mDevice = null
                mIsOpenCamera = false

                mainHandler?.post {
                    if (isFinishing || isDestroyed) return@post
                    homeBinding.ivStudent.visibility = View.GONE
                    homeBinding.lavAnimInit.visibility = View.GONE
                    homeBinding.llLavBorder.visibility = View.GONE
//                    homeBinding.lavAnimRecog.visibility = View.GONE
                    homeBinding.lavAnimInit.cancelAnimation()
                    homeBinding.lavAnimRecog.cancelAnimation()
                    colorSelected = 0
                    homeBinding.llImgBG.setBackgroundResource(R.drawable.bg_plam_not_recognized_55)

                    homeBinding.tvName.text = resources.getString(R.string.device_disconnected)
                    homeBinding.tvClassSec.text =
                        resources.getString(R.string.palm_detection_device_info)

                    homeBinding.lavAnimSuccess.visibility = View.GONE
                    homeBinding.lavAnimFail.visibility = View.GONE

                    homeBinding.tvDeviceStatus.text =
                        resources.getString(R.string.device_disconnected)
                    homeBinding.tvDeviceInfo.text =
                        resources.getString(R.string.tap_here_to_connect)
                    homeBinding.ivDeviceStatus.setImageResource(R.drawable.device_disconnected)
                }
            }
        }, object : DeviceStateListener {
            override fun onDevicePermissionGranted(d: DtUsbDevice?) {
            }

            override fun onDevicePermissionDenied(d: DtUsbDevice?) {
            }

            override fun onAttached(d: DtUsbDevice?) {

                algoStatus = EnableAlgorithmStatus.DISABLE
            }

            override fun onDetached(d: DtUsbDevice?) {

                mIsRunning = false
                mIsOpenCamera = false
                mainHandler?.post {
                    if (isFinishing || isDestroyed) return@post
                    homeBinding.ivStudent.visibility = View.GONE
                    homeBinding.lavAnimInit.visibility = View.GONE
                    homeBinding.llLavBorder.visibility = View.GONE
//                    homeBinding.lavAnimRecog.visibility = View.GONE
                    homeBinding.lavAnimInit.cancelAnimation()
                    homeBinding.lavAnimRecog.cancelAnimation()
                    colorSelected = 0
                    homeBinding.llImgBG.setBackgroundResource(R.drawable.bg_plam_not_recognized_55)

                    homeBinding.tvName.text = resources.getString(R.string.device_disconnected)
                    homeBinding.tvClassSec.text =
                        resources.getString(R.string.palm_detection_device_info)

                    homeBinding.lavAnimSuccess.visibility = View.GONE
                    homeBinding.lavAnimFail.visibility = View.GONE

                    homeBinding.tvDeviceStatus.text =
                        resources.getString(R.string.device_disconnected)
                    homeBinding.tvDeviceInfo.text =
                        resources.getString(R.string.tap_here_to_connect)
                    homeBinding.ivDeviceStatus.setImageResource(R.drawable.device_disconnected)

                    if (mTvDeviceInfo != null) mTvDeviceInfo!!.text = "DISCONNECTED"
                    if (mSwitchStartStream != null) mSwitchStartStream?.isChecked = false
                }
            }
        })
    }

    private fun open(device: IDevice) {
        device.open(object : IOpenCallback {
            override fun onDownloadPrepare() {
            }

            override fun onDownloadProgress(p: Int) {
            }

            override fun onDownloadSuccess() {
            }

            override fun onOpenSuccess() {

                mIsOpenCamera = true
                mDevice = device
                val info = (device as IVeinshine).deviceInfo
                val types = device.deviceSupportStreamType
                if (types != null && types.isNotEmpty()) {
                    currentStreamType = types[0]
                }
                mainHandler?.post {
                    if (isFinishing || isDestroyed) return@post
                    homeBinding.ivStudent.visibility = View.GONE
                    homeBinding.lavAnimInit.visibility = View.VISIBLE
                    homeBinding.llLavBorder.visibility = View.GONE
//                    homeBinding.lavAnimRecog.visibility = View.GONE
                    homeBinding.lavAnimInit.playAnimation()
                    homeBinding.lavAnimRecog.cancelAnimation()
//                    homeBinding.llImgBG.setBackgroundColor(Color.TRANSPARENT)

                    homeBinding.tvName.text = resources.getString(R.string.initializing)
                    homeBinding.tvClassSec.text = resources.getString(R.string.please_wait)

                    homeBinding.lavAnimSuccess.visibility = View.GONE
                    homeBinding.lavAnimFail.visibility = View.GONE

                    homeBinding.tvDeviceStatus.text = resources.getString(R.string.device_connected)
//                    homeBinding.tvDeviceStatus.text = resources.getString(R.string.device_connected) + " (${info.device_name})"
                    homeBinding.tvDeviceInfo.text = resources.getString(R.string.connection_stable)
                    homeBinding.ivDeviceStatus.setImageResource(R.drawable.device_connected)

//                    if (mSwitchStartStream != null) mSwitchStartStream?.isChecked = true
//                    enableDimPalm()

                    if (mTvDeviceInfo != null) mTvDeviceInfo!!.text = "READY: " + info.device_name
                    mListStreamType.clear()
                    mListStreamType.addAll(types)
                    if (mAdapterStreamType != null) mAdapterStreamType?.notifyDataSetChanged()
                }

//                if (mSwitchStartStream != null) mSwitchStartStream?.isChecked = true
                enableDimPalm()
            }

            override fun onOpenFail(e: Int) {
                showToast("Open error: $e")
            }
        })
    }

    private fun startStream() {
        val device = mDevice ?: return
        if (mIsRunning) return
        mIsRunning = true
        mStreamThread = Thread(Runnable {
            try {
                val type = currentStreamType
                if (type == StreamType.INVALID_STREAM_TYPE) {
                    mIsRunning = false
                    return@Runnable
                }
                val stream = device.createStream(type) ?: run {
                    mIsRunning = false
                    return@Runnable
                }
                val frames = stream.allocateFrames()
                if (stream.start() == 0) {
                    while (mIsRunning && mIsOpenCamera) {
                        if (stream.getFrames(frames, 2000) == 0) {
                            onDrawFrame(frames.getFrame(0), frames.getFrame(1))
                        } else {
                        }
                    }
                    if (mIsOpenCamera) {
                        try {
                            stream.stop()
                        } catch (e: Exception) {
                        }
                    }
                }
                if (mIsOpenCamera) {
                    try {
                        device.destroyStream(stream)
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
            } finally {
                mIsRunning = false
            }
        }, "StreamThread")
        mStreamThread?.start()
    }

    private fun onDrawFrame(f1: Frame?, f2: Frame?) {
        if (f1 != null) updateFrameData(f1)
        if (f2 != null) updateFrameData(f2)
        if (irFrameData1 != null && irDisPlay != null && mGLIrView != null) irDisPlay!!.render(
            mGLIrView,
            0,
            false,
            irFrameData1,
            irFrameW1,
            irFrameH1,
            2,
            if (irFrameExtraInfo != null) irFrameExtraInfo!!.palmRoi else null)
        if (rgbFrameData1 != null && rgbDisPlay != null && mGLRgbView != null) rgbDisPlay!!.render(
            mGLRgbView,
            0,
            false,
            rgbFrameData1,
            rgbFrameW1,
            rgbFrameH1,
            1,
            if (irFrameExtraInfo != null) irFrameExtraInfo!!.palmRoi else null)
    }

    private fun updateFrameData(f: Frame) {
        val type = f.frameType.name
        if (type.contains("RGB")) {
            rgbFrameW1 = f.width
            rgbFrameH1 = f.height
            rgbFrameData1 = f.rawData
        } else if (type.contains("IR")) {
            irFrameW1 = f.width
            irFrameH1 = f.height
            irFrameData1 = f.rawData
            irFrameExtraInfo = f.extraInfo
        }
    }

    private fun captureOnce() {
        val device = mDevice as? IVeinshine ?: return
        device.capturePalmOnce(mCapturePalmCallback, 15000, false)
    }

    private fun buildRgbBitmap(data: ByteArray, w: Int, h: Int) {
        val bits = ByteArray(data.size / 3 * 4)
        for (i in 0..<data.size / 3) {
            bits[i * 4] = data[i * 3 + 2]
            bits[i * 4 + 1] = data[i * 3 + 1]
            bits[i * 4 + 2] = data[i * 3]
            bits[i * 4 + 3] = -1
        }
        if (mRgbBitmap == null || mRgbBitmap?.width != w || mRgbBitmap?.height != h) mRgbBitmap =
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        mRgbBitmap?.copyPixelsFromBuffer(ByteBuffer.wrap(bits))
    }

    private fun buildIrBitmap(data: ByteArray, w: Int, h: Int) {
        val bits = ByteArray(data.size * 4)
        for (i in data.indices) {
            bits[i * 4 + 2] = data[i]
            bits[i * 4 + 1] = bits[i * 4 + 2]
            bits[i * 4] = bits[i * 4 + 1]
            bits[i * 4 + 3] = -1
        }
        if (mIrBitmap == null || mIrBitmap?.width != w || mIrBitmap?.height != h) mIrBitmap =
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        mIrBitmap!!.copyPixelsFromBuffer(ByteBuffer.wrap(bits))
    }

    private fun showCompareDialog() {
        val builder = AlertDialog.Builder(this)
        val v: View? = LayoutInflater.from(this).inflate(R.layout.dialog_compare_feature, null)
        builder.setView(v).setPositiveButton("Compare", null).show()
    }

    private fun showToast(text: String?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(c, text, Toast.LENGTH_SHORT).show()
        } else {
            runOnUiThread(Runnable { Toast.makeText(c, text, Toast.LENGTH_SHORT).show() })
        }
    }

    protected fun hideRect(v: DtRectRoiView) {
        v.setRect(BBox(0, 0, 0, 0), 0)
    }

    private fun stopCapture() {
        val device = mDevice as? IVeinshine ?: return
        device.stopPalmCapture()
    }

    private fun clearFrame() {
        irFrameData1 = null
        rgbFrameData1 = irFrameData1
    }

//    fun showProgressDialog(m: String?) {
//        progressDialog = DialogUtils.createLoadingDialog(this, m, false)
//        if (progressDialog != null) progressDialog.show()
//    }
//
//    fun dismissProgressDialog() {
//        if (progressDialog != null) progressDialog.dismiss()
//    }

    override fun onDestroy() {
        mIsRunning = false
        mainHandler?.removeCallbacksAndMessages(null)

        // Wait for StreamThread to finish BEFORE closing device or releasing displays
        // This avoids native crashes where the thread tries to use released resources.
        mStreamThread?.let {
            try {
                it.join(2500) // Wait up to 2.5s (getFrames has a 2s timeout)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
        mStreamThread = null

        // Stop any active capture and close the device handle to save power
        stopCapture()

        mDevice?.let { device ->
            deviceThread.execute {
                try {
                    android.util.Log.d(TAG, "Closing device on activity destroy")
                    device.close()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Error closing device: ${e.message}")
                }
            }
        }
        mDevice = null
        mIsOpenCamera = false

        // Release displays only after StreamThread has finished
        rgbDisPlay?.release()
        rgbDisPlay = null
        irDisPlay?.release()
        irDisPlay = null

        // Properly shutdown executors
        matchPool.shutdownNow()
        deviceThread.shutdown()

        super.onDestroy()
    }

    override fun attendanceSuccess() {

        setPendingAttendanceCount()
    }

    override fun attendanceFailed() {

    }

    override fun palmDetected() {

        /* colorSelected: 0 = Gray, 1 = Black, 2 = Gold, 3 = Blue, 4 = Pink */
        if (mIsRunning) {
            mainHandler?.post {
                if (isFinishing || isDestroyed) return@post
                colorSelected = 2
                homeBinding.llLavBorder.setBackgroundResource(R.drawable.bg_gold_55)
                homeBinding.llImgBG.setBackgroundResource(R.drawable.bg_gold_55)
                mainHandler?.postDelayed({

                    if (colorSelected == 2 && !isFinishing && !isDestroyed) {

                        homeBinding.llLavBorder.setBackgroundResource(R.drawable.bg_dodger_blue_55)
                        homeBinding.llImgBG.setBackgroundResource(R.drawable.bg_black_55)
                    }
                }, 500)
            }
        }
    }

    private fun setPendingAttendanceCount() {

        val attendanceCount = databaseHandler.getAttendanceCount()
        homeBinding.tvPendingCount.text = " | Pending: $attendanceCount"
        if (attendanceCount > 0) {

            homeBinding.tvPendingCount.setTextColor(resources.getColor(R.color.red))
        } else {

            homeBinding.tvPendingCount.setTextColor(resources.getColor(R.color.black))
        }
    }

    private fun loadLogoImg() {

        Picasso.get()
            .load(sharedPref.getLogoImgBaseUrl().toString() + sharedPref.getSchoolLogo().toString())
            .networkPolicy(NetworkPolicy.NO_CACHE).memoryPolicy(MemoryPolicy.NO_CACHE)
            .error(R.drawable.lokate_student).fit().centerCrop().into(homeBinding.imgLogo)
    }

    private fun showBackDialog() {

        if (databaseHandler.getAttendanceCount() > 0) {

            kotlinStatic.goBackDisabledDialog(resources.getString(R.string.unable_goback_info))
        } else {

            val backDialog = Dialog(c)
            val backBinding = BackDialogBinding.inflate(layoutInflater)
            backDialog.setContentView(backBinding.root)
            val exitWindow = backDialog.window
            exitWindow?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            exitWindow?.setLayout(kotlinStatic.getWidth() / 100 * 90,
                ConstraintLayout.LayoutParams.WRAP_CONTENT)
            backDialog.setCancelable(true)

            backBinding.tvNo.setOnClickListener { backDialog.dismiss() }
            backBinding.tvYes.setOnClickListener {

                backDialog.dismiss()
                gotoHome()
            }
            backDialog.show()
        }
    }
}