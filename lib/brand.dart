import 'package:flutter/material.dart';

import 'app_theme.dart';

/// The DownVid logo (same geometry as assets/brand/logo.svg, 108-unit grid):
/// a downward "play" triangle — a download arrow — over a tray.
class DownVidLogo extends StatelessWidget {
  const DownVidLogo({super.key, this.size = 40, this.background = true});

  final double size;

  /// false draws only the white mark (for use over the brand gradient).
  final bool background;

  @override
  Widget build(BuildContext context) => SizedBox.square(
    dimension: size,
    child: CustomPaint(painter: _LogoPainter(background)),
  );
}

class _LogoPainter extends CustomPainter {
  const _LogoPainter(this.background);
  final bool background;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.scale(size.width / 108);
    if (background) {
      const rect = Rect.fromLTWH(2, 2, 104, 104);
      final rrect = RRect.fromRectAndRadius(rect, const Radius.circular(26));
      canvas.drawRRect(rrect, Paint()..shader = AppTheme.brandGradient.createShader(rect));
      canvas.drawRRect(
        rrect,
        Paint()
          ..shader = const RadialGradient(
            center: Alignment(-0.4, -0.6),
            radius: 0.8,
            colors: [Color(0x38FFFFFF), Color(0x00FFFFFF)],
          ).createShader(rect),
      );
    }
    final stroke = Paint()
      ..color = Colors.white
      ..strokeWidth = 7
      ..strokeJoin = StrokeJoin.round
      ..strokeCap = StrokeCap.round;
    final triangle = Path()
      ..moveTo(36, 37)
      ..lineTo(72, 37)
      ..lineTo(54, 60)
      ..close();
    canvas.drawPath(triangle, Paint()..color = Colors.white);
    canvas.drawPath(triangle, stroke..style = PaintingStyle.stroke);
    canvas.drawLine(const Offset(37, 72.5), const Offset(71, 72.5), stroke);
  }

  @override
  bool shouldRepaint(_LogoPainter old) => old.background != background;
}

/// Rounded square with a soft tinted background, used for icons in lists,
/// tips and empty states.
class IconBadge extends StatelessWidget {
  const IconBadge(this.icon, {super.key, this.size = 40, this.color});

  final IconData icon;
  final double size;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final c = color ?? scheme.primary;
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(color: c.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(size * 0.3)),
      child: Icon(icon, color: c, size: size * 0.55),
    );
  }
}

/// Large icon + title + hint, centered; for empty lists.
class EmptyState extends StatelessWidget {
  const EmptyState({super.key, required this.icon, required this.title, required this.hint});

  final IconData icon;
  final String title;
  final String hint;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconBadge(icon, size: 72),
            const SizedBox(height: 16),
            Text(title, style: theme.textTheme.titleMedium, textAlign: TextAlign.center),
            const SizedBox(height: 6),
            Text(
              hint,
              textAlign: TextAlign.center,
              style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
          ],
        ),
      ),
    );
  }
}

/// Small uppercase-free section title used across screens.
class SectionTitle extends StatelessWidget {
  const SectionTitle(this.title, {super.key, this.trailing, this.padding});

  final String title;
  final Widget? trailing;
  final EdgeInsetsGeometry? padding;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: padding ?? const EdgeInsets.fromLTRB(4, 24, 4, 8),
      child: Row(
        children: [
          Expanded(
            child: Text(title, style: theme.textTheme.titleSmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
          ),
          ?trailing,
        ],
      ),
    );
  }
}
