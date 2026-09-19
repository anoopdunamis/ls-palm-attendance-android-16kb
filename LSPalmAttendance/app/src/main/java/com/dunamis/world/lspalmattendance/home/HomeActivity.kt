package com.dunamis.world.lspalmattendance.home

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dunamis.world.lspalmattendance.R
import com.dunamis.world.lspalmattendance.databinding.ActivityHomeBinding
import com.dunamis.world.lspalmattendance.databinding.EntryTypeDialogBinding
import com.dunamis.world.lspalmattendance.databinding.LogoutDialogBinding
import com.dunamis.world.lspalmattendance.databinding.PermissionLayoutBinding
import com.dunamis.world.lspalmattendance.db.DatabaseHandler
import com.dunamis.world.lspalmattendance.login.LoginActivity
import com.dunamis.world.lspalmattendance.recog.RecognitionActivity
import com.dunamis.world.lspalmattendance.statics.KotlinStatic
import com.dunamis.world.lspalmattendance.statics.SharedPref
import com.squareup.picasso.MemoryPolicy
import com.squareup.picasso.NetworkPolicy
import com.squareup.picasso.Picasso

class HomeActivity : AppCompatActivity(), HomeView {

    companion object {
        init {
            try {
                System.loadLibrary("c++_shared")
                System.loadLibrary("omp")
                System.loadLibrary("affinity_manager_ndk")
                System.loadLibrary("palm_sdk")
                System.loadLibrary("dim_palm")
                System.loadLibrary("stream_jni")

            } catch (e: UnsatisfiedLinkError) {
                e.printStackTrace()
            }
        }
    }

    private lateinit var c: Context
    private lateinit var homeBinding: ActivityHomeBinding
    private lateinit var sharedPref: SharedPref

    private var pageNo = 1
    private var pageRetries = 1
    private var palmPageNo = 1
    private var palmPageRetries = 1

    private lateinit var kotlinStatic: KotlinStatic

    private var isStudentAPIRunning = false
    private var isPalmAPIRunning = false
    private lateinit var databaseHandler: DatabaseHandler

    private lateinit var permissionDialog: Dialog
    private lateinit var permissionBinding: PermissionLayoutBinding

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->

            if (isGranted) {
                // Permission granted → proceed
                // TODO: your success logic here
            } else {
                val showRationale = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)

                if (!showRationale) {
                    // Permanently denied
                    permissionBinding.tvInfo.text = getString(R.string.permission_info_denied)
                    permissionBinding.btnOkay.text = getString(R.string.go_to_settings)

                    permissionBinding.btnOkay.setOnClickListener {

                        permissionDialog.dismiss()
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        })
                    }
                } else {
                    // Temporarily denied
                    permissionBinding.tvInfo.text = getString(R.string.permission_info)
                    permissionBinding.btnOkay.text = getString(R.string.okay)

                    permissionBinding.btnOkay.setOnClickListener {
                        permissionDialog.dismiss()
//                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
                permissionDialog.show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        homeBinding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(homeBinding.root)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.clHome)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        c = this;
        kotlinStatic = KotlinStatic(c)
        homeBinding.tvVersion.text = kotlinStatic.getVersionNo()
        sharedPref = SharedPref(c)
        databaseHandler = DatabaseHandler(c)

        if (sharedPref.getRecognitionPageTime()
                .equals(kotlinStatic.fetchDate(), ignoreCase = true)) {

            if (kotlinStatic.isAutoTimeEnabled()) {

                startActivity(Intent(c, RecognitionActivity::class.java))
                finishAffinity()
            } else {

                kotlinStatic.showAutoTimeZoneDialog()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showLogoutDialog()
            }
        })

        homeBinding.btnBack.setOnClickListener {

            showLogoutDialog()
        }

        homeBinding.llStart.setOnClickListener {

            if (kotlinStatic.isAutoTimeEnabled()) {

                if (isStudentAPIRunning || isPalmAPIRunning) {

                    kotlinStatic.goBackDisabledDialog(resources.getString(R.string.back_gesture_info))
                } else {

                    showEntryTypeDialog()
                }
            } else {

                kotlinStatic.showAutoTimeZoneDialog()
            }
        }

        homeBinding.tvDBCount.text =
            "DB Total: ${databaseHandler.getStudentCount()} | Palm Total: ${databaseHandler.getPalmCount()}"
        homeBinding.tvDBUpdatedDate.text = sharedPref.getStudentDBDownloadedTime()

        homeBinding.tvStart.setTextColor(resources.getColor(R.color.white))
        homeBinding.tvDBDownload.isEnabled = true
        homeBinding.tvDBDownload.setOnClickListener {

            if (homeBinding.tvDBDownload.isEnabled) {

                homeBinding.tvStart.setTextColor(resources.getColor(R.color.gray))
                homeBinding.tvDBDownload.isEnabled = false

                homeBinding.tvDBCount.text = "DB Total: 0 | Palm Total: 0"
                homeBinding.tvDBDownload.text =
                    "Downloading... DB: 0 / ${sharedPref.getTotalStudent()} | PALM: 0 / ${sharedPref.getTotalPalm()}"
                homeBinding.tvDBUpdatedDate.text = "---"

                isStudentAPIRunning = true
                isPalmAPIRunning = true

                pageNo = 1
                pageRetries = 1
                HomePresenter(c as HomeActivity).getAttendanceDetails(pageNo.toString())

                palmPageNo = 1
                palmPageRetries = 1
                HomePresenter(c as HomeActivity).getPalmDetails(palmPageNo.toString())
            }
        }

        permissionDialog = Dialog(c)
        permissionBinding = PermissionLayoutBinding.inflate(layoutInflater)
        permissionDialog.setContentView(permissionBinding.root)
        val permissionWindow = permissionDialog.window
        permissionWindow?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        permissionWindow?.setLayout(kotlinStatic.getWidth() / 100 * 90,
            ConstraintLayout.LayoutParams.WRAP_CONTENT)
        permissionDialog.setCancelable(false)

        if (sharedPref.getSchoolLogo().equals("", ignoreCase = true)) {

//            LoginPresenter(c as LoginActivity).logoReq(sharedPref.getBaseUrlRaw().toString())
        } else {

            loadLogoImg()
        }
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
    }

    override fun onResume() {
        super.onResume()

        if (databaseHandler.getStudentCount() == 0 || databaseHandler.getPalmCount() == 0 || sharedPref.getDBRefreshMandatory() == true) {

            homeBinding.tvDBDownload.performClick()
        }
    }

    override fun getAttendanceRes(data: Boolean) {

        if (data) {

            homeBinding.tvDBCount.text =
                "DB Total: ${databaseHandler.getStudentCount()} | Palm Total: ${databaseHandler.getPalmCount()}"
            homeBinding.tvDBDownload.text =
                "Downloading... DB: ${databaseHandler.getStudentCount()} / ${sharedPref.getTotalStudent()} | PALM: ${databaseHandler.getPalmCount()} / ${sharedPref.getTotalPalm()}"

            pageNo++
            pageRetries = 1
            HomePresenter(c as HomeActivity).getAttendanceDetails(pageNo.toString())
        } else {

            pageNo = 1
            pageRetries++
            HomePresenter(c as HomeActivity).getAttendanceDetails(pageNo.toString())
        }
    }

    override fun attendanceDownloaded() {

        homeBinding.tvDBCount.text =
            "DB Total: ${databaseHandler.getStudentCount()} | Palm Total: ${databaseHandler.getPalmCount()}"
        homeBinding.tvDBDownload.text =
            "Downloading... DB: ${databaseHandler.getStudentCount()} / ${sharedPref.getTotalStudent()} | PALM: ${databaseHandler.getPalmCount()} / ${sharedPref.getTotalPalm()}"

        sharedPref.setStudentDBDownloadedTime(kotlinStatic.getDBDownloadedTime())
        sharedPref.setDBRefreshMandatory(false)

        pageNo = 1
        pageRetries = 1

        isStudentAPIRunning = false
        if (!isPalmAPIRunning) {

            textViewChangeDownload()
        }
    }

    override fun attendanceDownloadFailed() {

        if (pageRetries > 4) {

            homeBinding.tvDBDownload.text = "DB DOWNLOADED FAILED"
            pageNo = 1
            pageRetries = 1

            isStudentAPIRunning = false
            if (isPalmAPIRunning) {

            } else {

                homeBinding.tvDBUpdatedDate.text = resources.getString(R.string.db_download_partial)
            }
            if (!isPalmAPIRunning) {

                textViewChangeDownload()
            }
        } else {

            HomePresenter(c as HomeActivity).getAttendanceDetails(pageNo.toString())
        }
        pageRetries++
    }

    override fun getPalmRes(data: Boolean) {

        if (data) {

            homeBinding.tvDBCount.text =
                "DB Total: ${databaseHandler.getStudentCount()} | Palm Total: ${databaseHandler.getPalmCount()}"
            homeBinding.tvDBDownload.text =
                "Downloading... DB: ${databaseHandler.getStudentCount()} / ${sharedPref.getTotalStudent()} | PALM: ${databaseHandler.getPalmCount()} / ${sharedPref.getTotalPalm()}"

            palmPageNo++
            palmPageRetries = 1
            HomePresenter(c as HomeActivity).getPalmDetails(palmPageNo.toString())
        } else {

            palmPageNo = 1
            palmPageRetries++
            HomePresenter(c as HomeActivity).getPalmDetails(palmPageNo.toString())
        }
    }

    override fun palmDownloaded() {

        homeBinding.tvDBCount.text =
            "DB Total: ${databaseHandler.getStudentCount()} | Palm Total: ${databaseHandler.getPalmCount()}"
        homeBinding.tvDBDownload.text =
            "Downloading... DB: ${databaseHandler.getStudentCount()} / ${sharedPref.getTotalStudent()} | PALM: ${databaseHandler.getPalmCount()} / ${sharedPref.getTotalPalm()}"

//        sharedPref.setStudentDBDownloadedTime(KotlinStatic().getDBDownloadedTime())
//        homeBinding.tvDBUpdatedDate.text = sharedPref.getStudentDBDownloadedTime()

        palmPageNo = 1
        palmPageRetries = 1

        isPalmAPIRunning = false
        if (!isStudentAPIRunning) {

            textViewChangeDownload()
        }
    }

    override fun palmDownloadFailed() {

        if (palmPageRetries > 4) {

            homeBinding.tvDBDownload.text = "DB DOWNLOADED FAILED"
            palmPageNo = 1
            palmPageRetries = 1

            isPalmAPIRunning = false
            if (isStudentAPIRunning) {

            } else {

                homeBinding.tvDBUpdatedDate.text = resources.getString(R.string.db_download_partial)
            }
            if (!isStudentAPIRunning) {

                textViewChangeDownload()
            }
        } else {

            HomePresenter(c as HomeActivity).getPalmDetails(palmPageNo.toString())
        }
        palmPageRetries++
    }

    private fun textViewChangeDownload() {

        object : CountDownTimer(2000, 1000) {

            override fun onTick(millisUntilFinished: Long) {
            }

            override fun onFinish() {

                homeBinding.tvDBUpdatedDate.text = sharedPref.getStudentDBDownloadedTime()
                homeBinding.tvStart.setTextColor(resources.getColor(R.color.white))
                homeBinding.tvDBDownload.isEnabled = true
                homeBinding.tvDBDownload.text =
                    resources.getString(R.string.tap_here_to_download_db)
            }
        }.start()
    }

    private fun gotoLogin() {

        startActivity(Intent(c, LoginActivity::class.java))
        finish()
    }

    private fun loadLogoImg() {

        Picasso.get()
            .load(sharedPref.getLogoImgBaseUrl().toString() + sharedPref.getSchoolLogo().toString())
            .networkPolicy(NetworkPolicy.NO_CACHE).memoryPolicy(MemoryPolicy.NO_CACHE)
            .error(R.drawable.lokate_student).fit().centerCrop().into(homeBinding.imgLogo)
    }

    private fun showLogoutDialog() {

        if (isStudentAPIRunning || isPalmAPIRunning) {

            kotlinStatic.goBackDisabledDialog(resources.getString(R.string.back_gesture_info))
        } else {

            val logoutDialog = Dialog(c)
            val logoutBinding = LogoutDialogBinding.inflate(layoutInflater)
            logoutDialog.setContentView(logoutBinding.root)
            val exitWindow = logoutDialog.window
            exitWindow?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            exitWindow?.setLayout(kotlinStatic.getWidth() / 100 * 90,
                ConstraintLayout.LayoutParams.WRAP_CONTENT)
            logoutDialog.setCancelable(true)

            logoutBinding.tvNo.setOnClickListener {

                logoutDialog.dismiss()
            }
            logoutBinding.tvYes.setOnClickListener {

                logoutDialog.dismiss()
                sharedPref.clearUpToLogin()
                databaseHandler.resetPalmTable()
                databaseHandler.resetStudentsTable()
                databaseHandler.resetAttendanceTable()
                gotoLogin()
            }
            logoutDialog.show()
        }
    }

    private fun showEntryTypeDialog() {

        val entryTypeDialog = Dialog(c)
        val entryTypeBinding = EntryTypeDialogBinding.inflate(layoutInflater)
        entryTypeDialog.setContentView(entryTypeBinding.root)
        val exitWindow = entryTypeDialog.window
        exitWindow?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        exitWindow?.setLayout(kotlinStatic.getWidth() / 100 * 97,
            ConstraintLayout.LayoutParams.WRAP_CONTENT)
        entryTypeDialog.setCancelable(true)

        val intent = Intent(c, RecognitionActivity::class.java)
        entryTypeBinding.tvEntry.setOnClickListener {

            entryTypeDialog.dismiss()
            if (kotlinStatic.isAutoTimeEnabled()) {

                sharedPref.setEntryType("in")
                sharedPref.setRecognitionPageTime(kotlinStatic.fetchDate())
                startActivity(intent)
                finish()
            }
        }
        entryTypeBinding.tvExit.setOnClickListener {

            entryTypeDialog.dismiss()
            if (kotlinStatic.isAutoTimeEnabled()) {

                sharedPref.setEntryType("out")
                sharedPref.setRecognitionPageTime(kotlinStatic.fetchDate())
                startActivity(intent)
                finish()
            }
        }
        entryTypeDialog.show()
    }
}