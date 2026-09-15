package com.dunamis.world.lspalmattendance.login

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dunamis.world.lspalmattendance.R
import com.dunamis.world.lspalmattendance.databinding.ActivityLoginBinding
import com.dunamis.world.lspalmattendance.databinding.ErrorDialogBinding
import com.dunamis.world.lspalmattendance.databinding.PermissionLayoutBinding
import com.dunamis.world.lspalmattendance.server.ServerActivity
import com.dunamis.world.lspalmattendance.statics.KotlinStatic
import com.dunamis.world.lspalmattendance.statics.SharedPref
import com.squareup.picasso.MemoryPolicy
import com.squareup.picasso.NetworkPolicy
import com.squareup.picasso.Picasso
import kotlin.toString
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.dunamis.world.lspalmattendance.databinding.BackDialogBinding
import com.dunamis.world.lspalmattendance.home.HomeActivity

class LoginActivity : AppCompatActivity(), LoginView {

    private lateinit var c: Context
    private lateinit var homeBinding: ActivityLoginBinding
    private lateinit var sharedPref: SharedPref
    private lateinit var errorDialog: Dialog
    private lateinit var errorBinding: ErrorDialogBinding

    private lateinit var kotlinStatic: KotlinStatic

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
        homeBinding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(homeBinding.root)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rlLogin)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        c = this
        kotlinStatic = KotlinStatic(c)
        homeBinding.tvVersion.text = kotlinStatic.getVersionNo()
        sharedPref = SharedPref(c)

        if (sharedPref.getLoggedIn()) {

            goToHome()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showBackDialog()
            }
        })

        errorDialog = Dialog(c)
        errorBinding = ErrorDialogBinding.inflate(layoutInflater)
        errorDialog.setContentView(errorBinding.root)

        val window = errorDialog.window
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setLayout(kotlinStatic.getWidth() / 100 * 90,
            ConstraintLayout.LayoutParams.WRAP_CONTENT)

        val lp = errorDialog.window?.attributes
        lp?.dimAmount = 0.76f

        errorBinding.tvOkay.setOnClickListener {

            homeBinding.btnSubmit.isEnabled = true
            errorDialog.dismiss()
        }

        errorDialog.setOnDismissListener {

            homeBinding.btnSubmit.isEnabled = true
        }

        homeBinding.btnBack.setOnClickListener {

            showBackDialog()
        }

        homeBinding.btnSubmit.setOnClickListener {

            val u: String = homeBinding.etUsername.text.toString().trim()
            val p: String = homeBinding.etPassword.text.toString().trim()

            if (u.equals("", ignoreCase = true)) {

                homeBinding.etUsername.error = "Please enter username"
            } else if (p.equals("", ignoreCase = true)) {

                homeBinding.etPassword.error = "Please enter passcode"
            } else {

                errorDialog.setCancelable(false)
                errorBinding.lavAnim.playAnimation()
                errorDialog.show()
                errorBinding.lavAnim.visibility = View.VISIBLE
                errorBinding.llError.visibility = View.GONE

                homeBinding.btnSubmit.isEnabled = false
                LoginPresenter(c as LoginActivity).login(u, p, sharedPref.getBaseUrl().toString())
            }
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(homeBinding.btnSubmit.windowToken, 0)
        }

        homeBinding.etPassword.setOnKeyListener(View.OnKeyListener { v, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {

                homeBinding.btnSubmit.performClick()
                return@OnKeyListener true
            }
            false
        })

        permissionDialog = Dialog(c)
        permissionBinding = PermissionLayoutBinding.inflate(layoutInflater)
        permissionDialog.setContentView(permissionBinding.root)
        val permissionWindow = permissionDialog.window
        permissionWindow?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        permissionWindow?.setLayout(kotlinStatic.getWidth() / 100 * 90,
            ConstraintLayout.LayoutParams.WRAP_CONTENT)
        permissionDialog.setCancelable(false)

        if (sharedPref.getSchoolLogo().equals("", ignoreCase = true)) {

            LoginPresenter(c as LoginActivity).logoReq(sharedPref.getBaseUrlRaw().toString())
        } else {

            loadLogoImg()
        }
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
    }

    override fun success(faceAuth: String, scanType: String) {

        sharedPref.setFaceReg(faceAuth)
        sharedPref.setScanType(scanType)
        errorBinding.lavAnim.cancelAnimation()
        errorDialog.dismiss()

        goToHome()
    }

    override fun logoImgRes(logoImgUrl: String) {

        sharedPref.setSchoolLogo(logoImgUrl)
        loadLogoImg()
    }

    override fun failed(msg: String) {

        errorBinding.tvInfo.text = msg

        errorBinding.lavAnim.cancelAnimation()
        errorBinding.lavAnim.visibility = View.GONE
        errorBinding.llError.visibility = View.VISIBLE
        errorDialog.setCancelable(true)
    }

    private fun goToHome() {

        sharedPref.setLoggedIn(true)
        startActivity(Intent(c, HomeActivity::class.java))
        finish()
    }

    private fun loadLogoImg() {

        Picasso.get()
            .load(sharedPref.getLogoImgBaseUrl().toString() + sharedPref.getSchoolLogo().toString())
            .networkPolicy(NetworkPolicy.NO_CACHE).memoryPolicy(MemoryPolicy.NO_CACHE)
            .error(R.drawable.lokate_student).fit().centerCrop().into(homeBinding.imgLogo)
    }

    private fun showBackDialog() {

        val backDialog = Dialog(c)
        val backBinding = BackDialogBinding.inflate(layoutInflater)
        backDialog.setContentView(backBinding.root)
        val exitWindow = backDialog.window
        exitWindow?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        exitWindow?.setLayout(kotlinStatic.getWidth() / 100 * 90,
            ConstraintLayout.LayoutParams.WRAP_CONTENT)
        backDialog.setCancelable(true)

        backBinding.tvNo.setOnClickListener {

            backDialog.dismiss()
        }
        backBinding.tvYes.setOnClickListener {

            backDialog.dismiss()
            sharedPref.clearAll()
            startActivity(Intent(c, ServerActivity::class.java))
            finishAffinity()
        }
        backDialog.show()
    }
}