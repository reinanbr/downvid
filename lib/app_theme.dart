import 'package:flutter/material.dart';

abstract final class AppTheme {
  static const violet = Color(0xFF7B5CFF);
  static const pink = Color(0xFFFF4F8B);

  /// The logo's gradient, reused for the home hero and highlights.
  static const brandGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [violet, pink],
  );

  static final light = _build(Brightness.light);
  static final dark = _build(Brightness.dark);

  static ThemeData _build(Brightness brightness) {
    final scheme = ColorScheme.fromSeed(
      seedColor: violet,
      brightness: brightness,
      dynamicSchemeVariant: DynamicSchemeVariant.vibrant,
    );
    final base = ThemeData(colorScheme: scheme, useMaterial3: true);
    final text = base.textTheme;
    const radius = BorderRadius.all(Radius.circular(16));
    const buttonShape = RoundedRectangleBorder(borderRadius: radius);
    const buttonSize = Size(64, 52);
    final buttonText = text.titleSmall?.copyWith(fontWeight: FontWeight.w600);

    return base.copyWith(
      scaffoldBackgroundColor: scheme.surface,
      textTheme: text.copyWith(
        headlineSmall: text.headlineSmall?.copyWith(fontWeight: FontWeight.w700, letterSpacing: -0.3),
        titleLarge: text.titleLarge?.copyWith(fontWeight: FontWeight.w700, letterSpacing: -0.2),
        titleMedium: text.titleMedium?.copyWith(fontWeight: FontWeight.w600),
        titleSmall: text.titleSmall?.copyWith(fontWeight: FontWeight.w600),
      ),
      appBarTheme: AppBarTheme(
        backgroundColor: scheme.surface,
        surfaceTintColor: Colors.transparent,
        scrolledUnderElevation: 0,
        // ThemeData.textTheme carries family/color; sizes come from typography.englishLike.
        titleTextStyle: base.typography.englishLike.titleLarge
            ?.merge(text.titleLarge)
            .copyWith(fontWeight: FontWeight.w700, letterSpacing: -0.2, color: scheme.onSurface),
      ),
      cardTheme: CardThemeData(
        elevation: 0,
        margin: EdgeInsets.zero,
        color: scheme.surfaceContainerLow,
        clipBehavior: Clip.antiAlias,
        shape: const RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(20))),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: scheme.surfaceContainerHighest,
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
        border: const OutlineInputBorder(borderRadius: radius, borderSide: BorderSide.none),
        enabledBorder: const OutlineInputBorder(borderRadius: radius, borderSide: BorderSide.none),
        focusedBorder: OutlineInputBorder(
          borderRadius: radius,
          borderSide: BorderSide(color: scheme.primary, width: 2),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(minimumSize: buttonSize, shape: buttonShape, textStyle: buttonText),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(minimumSize: buttonSize, shape: buttonShape, textStyle: buttonText),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(shape: buttonShape, textStyle: buttonText),
      ),
      segmentedButtonTheme: SegmentedButtonThemeData(
        style: SegmentedButton.styleFrom(
          minimumSize: const Size(0, 44),
          shape: buttonShape,
          side: BorderSide(color: scheme.outlineVariant),
        ),
      ),
      listTileTheme: const ListTileThemeData(
        contentPadding: EdgeInsets.symmetric(horizontal: 16),
        shape: RoundedRectangleBorder(borderRadius: radius),
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: scheme.surfaceContainerLow,
        surfaceTintColor: Colors.transparent,
        showDragHandle: true,
        shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(28))),
      ),
      dialogTheme: const DialogThemeData(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(28))),
      ),
      snackBarTheme: const SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: radius),
      ),
      chipTheme: ChipThemeData(
        shape: const StadiumBorder(),
        side: BorderSide(color: scheme.outlineVariant),
        backgroundColor: scheme.surface,
        labelStyle: text.labelLarge,
      ),
      progressIndicatorTheme: ProgressIndicatorThemeData(
        linearTrackColor: scheme.surfaceContainerHighest,
        linearMinHeight: 6,
        borderRadius: const BorderRadius.all(Radius.circular(3)),
      ),
      tabBarTheme: TabBarThemeData(
        dividerColor: scheme.outlineVariant.withValues(alpha: 0.4),
        labelStyle: text.titleSmall?.copyWith(fontWeight: FontWeight.w600),
      ),
    );
  }
}

/// Semantic colors not covered by [ColorScheme].
extension AppColors on ColorScheme {
  Color get success => brightness == Brightness.light ? const Color(0xFF1E8E5A) : const Color(0xFF6FD9A0);
  Color get successContainer => brightness == Brightness.light ? const Color(0xFFD7F5E4) : const Color(0xFF0F3D27);
  Color get warning => brightness == Brightness.light ? const Color(0xFFB26A00) : const Color(0xFFFFC266);
}
