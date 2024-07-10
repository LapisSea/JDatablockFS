package com.lapissea.dfs.query;

import com.lapissea.dfs.type.SupportedPrimitive;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public enum QueryUtils{
	;
	
	public static Class<?> addTyp(Class<?> l, Class<?> r){
		var lt = SupportedPrimitive.get(l).orElseThrow();
		var rt = SupportedPrimitive.get(r).orElseThrow();
		
		if(lt.getType() == rt.getType()) return lt.getType();
		
		var pr = promoteTo(lt, rt);
		if(pr != null) return pr.getType();
		var pl = promoteTo(rt, lt);
		if(pl != null) return pl.getType();
		throw new RuntimeException(lt + " " + rt);
	}
	private static final Map<SupportedPrimitive, List<SupportedPrimitive>> PROMOTION_MAP = Map.of(
		SupportedPrimitive.BYTE, List.of(SupportedPrimitive.SHORT),
		SupportedPrimitive.SHORT, List.of(SupportedPrimitive.INT),
		SupportedPrimitive.CHAR, List.of(SupportedPrimitive.SHORT),
		SupportedPrimitive.INT, List.of(SupportedPrimitive.LONG, SupportedPrimitive.FLOAT),
		SupportedPrimitive.LONG, List.of(SupportedPrimitive.DOUBLE),
		SupportedPrimitive.FLOAT, List.of(SupportedPrimitive.DOUBLE)
	);
	
	private static void deepPromotion(SupportedPrimitive l, Set<SupportedPrimitive> result){
		result.add(l);
		while(true){
			var wave = Iters.from(result).map(PROMOTION_MAP::get).nonNulls().flatMap(e -> e).collectToList();
			if(!result.addAll(wave)){
				break;
			}
		}
	}
	
	private static SupportedPrimitive promoteTo(SupportedPrimitive l, SupportedPrimitive r){
		var deepL = new LinkedHashSet<SupportedPrimitive>();
		var deepR = EnumSet.noneOf(SupportedPrimitive.class);
		deepPromotion(l, deepL);
		deepPromotion(r, deepR);
		
		return Iters.from(deepL).filtered(deepR::contains).minBy(c -> c.maxSize.get()).orElse(null);
	}
}
