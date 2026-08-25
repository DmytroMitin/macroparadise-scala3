package com.example.core

import com.example.`macro`.annotations.gen

@gen
class GenUser

val documentedGreeting: String = new GenUser().generatedHello
