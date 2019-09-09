package com.example.edgygl.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.Fragment
import com.example.edgygl.R
import kotlinx.android.synthetic.main.fragment_camera_permissions_rationale.view.*

/**
 * A simple [Fragment] subclass.
 * Use the [CameraPermissionsFragment.newInstance] factory method to
 * create an instance of this fragment.
 *
 */
class CameraPermissionsFragment : androidx.fragment.app.DialogFragment() {

    companion object {

        private const val ARG_PARAM1 = "param1"
        private const val ARG_PARAM2 = "param2"

        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param param1 Parameter 1.
         * @param param2 Parameter 2.
         * @return A new instance of fragment CameraPermissionsRationaleFragment.
         */
        @JvmStatic
        fun newInstance(param1: String, param2: String): CameraPermissionsFragment =
                CameraPermissionsFragment().apply {
                    arguments = Bundle().apply {
                        putString(ARG_PARAM1, param1)
                        putString(ARG_PARAM2, param2)
                    }
                    return CameraPermissionsFragment()
                }
    }

    private var param1: String? = null
    private var param2: String? = null

    /**
     * onCreate()
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.AppTheme)

        arguments?.let {
            param1 = it.getString(ARG_PARAM1)
            param2 = it.getString(ARG_PARAM2)
        }
    }

    /**
     * onCreateView()
     */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {

        val rootView = inflater.inflate(R.layout.fragment_camera_permissions_rationale, container, false)

        // magic to keep app in immersive mode
        dialog?.window?.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        activity?.window?.decorView?.systemUiVisibility?.let {  dialog?.window?.decorView?.systemUiVisibility = it }

        dialog?.setOnShowListener {
            // Clear the not focusable flag from the window
            dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

            // Update the WindowManager with the new attributes (no nicer way I know of to do this)..
            val wm = activity?.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.updateViewLayout(dialog?.window?.decorView, dialog?.window?.attributes)
        }

        // Add animations
        dialog?.window?.attributes?.windowAnimations = R.style.DialogAnimation

        // Setup a toolbar for this fragment
        val toolbar = rootView.cameraRationaleToolbar
        toolbar.setNavigationIcon(R.drawable.ic_chevron_left_24dp)
        toolbar.setNavigationOnClickListener { dismiss() }
        toolbar.setTitle(R.string.permissions_title_camera)

        // Inflate the layout for this fragment
        return rootView
    }
}
