package com.school.smartcbt.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.MenuItem
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.school.smartcbt.ui.*

/**
 * SharedNavigationHelper (Modul 10 & 13)
 * Mengelola navigasi bottom bar 4-5 item yang terstandarisasi untuk semua role pengguna:
 * - SISWA:     Beranda, Tugas, Absensi, Pesan, Profil
 * - GURU:      Beranda, Kelas Saya, Tugas/CBT, Pesan, Profil
 * - ORANG TUA: Beranda, Akademik, Kesehatan/BK, Pesan, Profil
 * - OPERATOR:  Beranda, Modul, Pengumuman, Server, Profil
 * - BK:        Beranda, Kasus, Kalender, Pesan, Profil
 */
object SharedNavigationHelper {

    enum class Role {
        STUDENT, TEACHER, PARENT, OPERATOR, COUNSELOR, ADMIN
    }

    data class NavItem(
        val id: Int,
        val title: String,
        val iconResId: Int,
        val targetActivityClass: Class<out Activity>? = null
    )

    fun setupBottomNav(
        context: Context,
        bottomNav: BottomNavigationView,
        role: Role,
        currentTabId: Int,
        onTabSelected: ((Int) -> Boolean)? = null
    ) {
        // Enforce consistent styling
        bottomNav.itemIconTintList = null // Allow custom icon tinting
        
        bottomNav.setOnItemSelectedListener { item: MenuItem ->
            if (item.itemId == currentTabId) {
                return@setOnItemSelectedListener true
            }

            if (onTabSelected != null && onTabSelected(item.itemId)) {
                return@setOnItemSelectedListener true
            }

            handleDefaultNavigation(context, item.itemId, role)
            true
        }
    }

    private fun handleDefaultNavigation(context: Context, itemId: Int, role: Role) {
        when (itemId) {
            // Profil Navigasi Bersama (Semua Role)
            android.R.id.home -> {
                // Return to home activity based on role
                when (role) {
                    Role.STUDENT -> navigateTo(context, StudentMainActivity::class.java)
                    Role.TEACHER -> navigateTo(context, TeacherMainActivity::class.java)
                    Role.PARENT -> navigateTo(context, ParentMainActivity::class.java)
                    Role.OPERATOR, Role.ADMIN -> navigateTo(context, OperatorMainActivity::class.java)
                    Role.COUNSELOR -> navigateTo(context, BkActivity::class.java)
                }
            }
        }
    }

    fun applyBadge(bottomNav: BottomNavigationView, menuItemId: Int, count: Int) {
        val badge = bottomNav.getOrCreateBadge(menuItemId)
        if (count > 0) {
            badge.isVisible = true
            badge.number = count
        } else {
            badge.isVisible = false
        }
    }

    private fun navigateTo(context: Context, targetClass: Class<out Activity>) {
        if (context::class.java != targetClass) {
            val intent = Intent(context, targetClass)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
        }
    }
}
