package com.lapissea.dfs.internal;

import com.lapissea.dfs.internal.Access.Mode;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.util.UtilL;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.List;

public final class AccessUtils{
	
	static IterablePP<Mode> getModes(MethodHandles.Lookup lookup){
		return Iters.from(Mode.values())
		            .filter(e -> UtilL.checkFlag(lookup.lookupModes(), e.flag));
	}
	
	static MethodHandles.Lookup stripModes(MethodHandles.Lookup lookup, Mode[] requiredModes){
		var toStrip = EnumSet.allOf(Mode.class);
		toStrip.removeAll(List.of(Mode.ORIGINAL, Mode.UNCONDITIONAL));
		
		while(!toStrip.isEmpty()){
			var current = lookup;
			var okStrips = Iters.from(toStrip).filter(mode -> {
				var nm     = current.dropLookupMode(mode.flag);
				var sModes = getModes(nm).toSet();
				return Iters.from(requiredModes).allMatch(sModes::contains);
			}).matchFirst();
			if(okStrips instanceof Match.Some(var okMode)){
				lookup = current.dropLookupMode(okMode.flag);
				toStrip.remove(okMode);
			}else{
				break;
			}
		}
		return lookup;
	}
	
	static boolean isPublicMode(Mode[] modes){
		for(Mode mode : modes){
			if(mode != Mode.PUBLIC) return false;
		}
		return true;
	}
	
	static MethodHandles.Lookup adaptLookupTo(MethodHandles.Lookup lookup, Class<?> clazz, Mode[] modes) throws IllegalAccessException{
		MethodHandles.Lookup actualLookup;
		try{
			actualLookup = MethodHandles.privateLookupIn(clazz, lookup);
		}catch(IllegalAccessException e){
			throw new IllegalAccessException(Log.fmt("{}#red is not accessible from {}#yellow: {}", clazz, lookup, e.getMessage()));
		}
		
		return AccessUtils.stripModes(actualLookup, modes);
	}
	
	static void requireModes(MethodHandles.Lookup lookup, Mode... requiredModes){
		if(!hasModes(lookup, requiredModes)){
			badLookup(lookup);
		}
	}
	
	static boolean hasModes(MethodHandles.Lookup lookup, Mode... requiredModes){
		var actualModes = getModes(lookup).toModSet();
		return Iters.from(requiredModes).allMatch(actualModes::contains);
	}
	
	private static void badLookup(MethodHandles.Lookup lookup){
		var modes = getModes(lookup).joinAsStr(", ", "[", "]");
		
		throw new IllegalArgumentException(
			Log.fmt("Lookup of {}#red must have {#yellow[PRIVATE, MODULE]#} access but has {}#yellow!", lookup, modes)
		);
	}
	
	static Mode modeFromModifiers(int modifiers){
		return Iters.of(Mode.PRIVATE, Mode.PROTECTED, Mode.PACKAGE, Mode.PUBLIC)
		            .firstMatching(m -> (modifiers&m.flag) != 0)
		            .orElse(Mode.PACKAGE);
	}
	
	static <FInter> Method getFunctionalMethod(Class<FInter> functionalInterface){
		var methods = Iters.from(functionalInterface.getMethods())
		                   .filter(m -> !Modifier.isStatic(m.getModifiers()) && Modifier.isAbstract(m.getModifiers()))
		                   .limit(2)
		                   .toModList();
		if(methods.size() != 1){
			throw new IllegalArgumentException(functionalInterface + " is not a functional interface!");
		}
		return methods.getFirst();
	}
}
