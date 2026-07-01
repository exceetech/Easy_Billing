package com.example.easy_billing

import android.os.Bundle

/**
 * Thin host activity for [ProfileFragment].
 *
 * Kept lightweight on purpose so the drawer can launch it like every
 * other top-level navigation destination in the app while the actual
 * UI lives inside the fragment (lifecycle-aware ViewModel scope).
 */
class ProfileActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = getString(R.string.profile)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.profileContainer, ProfileFragment.newInstance())
                .commit()
        }
    }
}
