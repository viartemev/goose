package com.goose.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val GooseTypography =
    Typography(
        displayLarge =
            TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = 64.sp,
                lineHeight = 68.sp,
                letterSpacing = (-1).sp,
            ),
        headlineMedium =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                lineHeight = 34.sp,
                letterSpacing = 0.sp,
            ),
        titleMedium =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                lineHeight = 22.sp,
                letterSpacing = 0.sp,
            ),
        bodyLarge =
            TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = 17.sp,
                lineHeight = 22.sp,
                letterSpacing = 0.sp,
            ),
        bodyMedium =
            TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.sp,
            ),
        labelSmall =
            TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.sp,
            ),
    )
