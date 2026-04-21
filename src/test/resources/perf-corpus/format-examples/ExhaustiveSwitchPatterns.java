package com.example;

sealed interface Shape permits Circle, Square, Rectangle, Triangle {}
record Circle(double radius) implements Shape {}
record Square(double side) implements Shape {}
record Rectangle(double width, double height) implements Shape {}
record Triangle(double a, double b, double c) implements Shape {}

class ExhaustiveSwitchPatterns {
    double area(Shape s) {
        return switch (s) {
            case Circle c when c.radius() > 0 -> Math.PI * c.radius() * c.radius();
            case Circle c -> 0.0;
            case Square(double side) when side > 0 -> side * side;
            case Square sq -> 0.0;
            case Rectangle(double w, double h) when w > 0 && h > 0 -> w * h;
            case Rectangle r -> 0.0;
            case Triangle(double a, double b, double c) -> {
                var s2 = (a + b + c) / 2;
                yield Math.sqrt(s2 * (s2 - a) * (s2 - b) * (s2 - c));
            }
        };
    }
}
