package com.example.macros.annotations

import paradise3.api.expander
import scala.annotation.StaticAnnotation

@expander("com.example.macros.handlers.GenHandler")
final class gen extends StaticAnnotation
