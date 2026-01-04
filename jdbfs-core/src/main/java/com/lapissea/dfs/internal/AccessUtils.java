package com.lapissea.dfs.internal;

import com.lapissea.dfs.internal.Access.Mode;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.util.UtilL;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public final class AccessUtils{
	
	private static final Mode[] ALL_MODES = Mode.values();
	static EnumSet<Mode> getModes(MethodHandles.Lookup lookup){
		var modes = EnumSet.noneOf(Mode.class);
		for(Mode mode : ALL_MODES){
			if(UtilL.checkFlag(lookup.lookupModes(), mode.flag)){
				modes.add(mode);
			}
		}
		return modes;
	}
	
	static MethodHandles.Lookup stripModes(MethodHandles.Lookup lookup, Mode[] requiredModes){
		var toStrip = EnumSet.allOf(Mode.class);
		toStrip.removeAll(List.of(Mode.ORIGINAL, Mode.UNCONDITIONAL));
		
		while(!toStrip.isEmpty()){
			var current = lookup;
			var okStrips = Iters.from(toStrip).filter(mode -> {
				var nm     = current.dropLookupMode(mode.flag);
				var sModes = getModes(nm);
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
	
	public static MethodHandles.Lookup adaptLookupTo(MethodHandles.Lookup lookup, Class<?> clazz) throws IllegalAccessException{
		MethodHandles.Lookup actualLookup;
		if(lookup.lookupClass() == clazz){
			actualLookup = lookup;
		}else{
			actualLookup = MethodHandles.privateLookupIn(clazz, lookup);
		}
		
		return actualLookup;
	}
	private static IllegalAccessException fail(Class<?> clazz, MethodHandles.Lookup lookup, IllegalAccessException e){
		return new IllegalAccessException(Log.fmt("{}#red is not accessible from {}#yellow: {}", clazz, lookup, e.getMessage()));
	}
	
	static Optional<Supplier<String>> requireModes(MethodHandles.Lookup lookup, Mode... requiredModes){
		if(hasModes(lookup, requiredModes)){
			return Optional.empty();
		}
		return Optional.of(() -> Log.fmt(
			"Lookup of {}#red must have {}#yellow access but has {}#yellow!",
			lookup,
			Iters.from(requiredModes).joinAsStr(", ", "[", "]"),
			Iters.from(getModes(lookup)).joinAsStr(", ", "[", "]")
		));
	}
	
	static boolean hasModes(MethodHandles.Lookup lookup, Mode... requiredModes){
		var actualModes = getModes(lookup);
		for(Mode requiredMode : requiredModes){
			if(!actualModes.contains(requiredMode)){
				return false;
			}
		}
		return true;
	}
	
	public static Mode modeFromModifiers(int modifiers){
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
