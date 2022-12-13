package com.lapissea.jorth.lang.type;

import com.lapissea.jorth.lang.ClassName;

import java.util.Map;

public record AnnGen(ClassName type, Map<String, Object> args){
}
