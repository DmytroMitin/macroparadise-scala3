package com.example.macros.annotations

import paradise3.api.expander
import scala.annotation.StaticAnnotation

@expander("com.example.macros.handlers.IdentityHandler")
class identity extends StaticAnnotation
