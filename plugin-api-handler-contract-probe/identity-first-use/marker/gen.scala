package com.example.`macro`.annotations

import paradise3.api.expander
import scala.annotation.StaticAnnotation

@expander("com.example.macro.handlers.GenHandler")
final class gen extends StaticAnnotation
