import 'package:flutter/material.dart';

abstract final class AppTheme {
  static const _seed = Color(0xFF1E88E5);

  static final light = ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: _seed));

  static final dark = ThemeData(
    colorScheme: ColorScheme.fromSeed(seedColor: _seed, brightness: Brightness.dark),
  );
}
