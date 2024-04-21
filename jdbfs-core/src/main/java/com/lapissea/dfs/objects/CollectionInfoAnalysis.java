package com.lapissea.dfs.objects;

import com.lapissea.dfs.objects.CollectionInfo.ArrayInfo;
import com.lapissea.dfs.objects.CollectionInfo.Layout;
import com.lapissea.dfs.objects.CollectionInfo.ListInfo;
import com.lapissea.dfs.objects.CollectionInfo.PrimitiveArrayInfo;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.utils.IterablePP;
import com.lapissea.dfs.utils.IterablePPs;
import com.lapissea.util.UtilL;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public abstract class CollectionInfoAnalysis{
	
	private static final Set<Class<?>> UNMODIFIABLE_LISTS;
	
	static{
		Set<Class<?>> ul = new HashSet<>();
		var           l  = new ArrayList<>(20);
		for(int i = 0; i<20; i++){
			ul.add(List.copyOf(l).getClass());
			l.add(i);
		}
		ul.add(Collections.unmodifiableList(new ArrayList<>()).getClass());
		ul.add(Collections.unmodifiableList(new LinkedList<>()).getClass());
		
		UNMODIFIABLE_LISTS = Set.copyOf(ul);
	}
	
	public static CollectionInfo analyze(Object collection){
		if(collection == null){
			return CollectionInfo.NullValue.INSTANCE;
		}
		
		if(collection.getClass().isArray()){
			return analyzeArray(collection);
		}
		
		if(collection instanceof List<?> list){
			return analyzeList(list);
		}
		
		return null;
	}
	
	private static CollectionInfo analyzeArray(Object array){
		var componentType = array.getClass().componentType();
		if(componentType.isPrimitive()){
			return new PrimitiveArrayInfo(Array.getLength(array), componentType);
		}
		
		var arr = (Object[])array;
		
		var result = analyseElements(IterablePPs.of(arr));
		return new ArrayInfo(arr.getClass(), arr.length, result.constType(), result.layout(), result.hasNulls());
	}
	
	private static CollectionInfo analyzeList(List<?> list){
		var result = analyseElements(IterablePPs.from(list));
		var unMod  = UNMODIFIABLE_LISTS.contains(list.getClass());
		return new ListInfo(list.size(), result.constType(), result.layout(), result.hasNulls(), unMod);
	}
	
	private record ElementsResult(Class<?> constType, boolean hasNulls, Layout layout){ }
	private static ElementsResult analyseElements(IterablePP<?> data){
		Class<?> constType = null;
		boolean  hasNulls  = false;
		var      layout    = Layout.STRIPED;
		boolean  uModList  = false;
		
		for(var el : data){
			if(el == null){
				hasNulls = true;
				continue;
			}
			var eTyp = el.getClass();
			if(layout != Layout.DYNAMIC){
				if(constType == null){
					constType = eTyp;
					uModList = UNMODIFIABLE_LISTS.contains(eTyp);
				}else if(constType != eTyp){
					if(uModList && UNMODIFIABLE_LISTS.contains(eTyp)){//Ignore class difference for ulist
						constType = eTyp;
						continue;
					}
					layout = Layout.DYNAMIC;
					constType = null;
				}
			}
		}
		
		if(constType != null){
			layout = Struct.tryOf(constType).filter(s -> !(s instanceof Struct.Unmanaged)).map(s -> {
				return switch(IOFieldTools.minWordSpace(s.getFields())){
					case BIT -> Layout.STRUCT_OF_ARRAYS;
					case BYTE -> Layout.STRIPED;
				};
			}).orElse(layout);
			
			if(layout == Layout.STRUCT_OF_ARRAYS){
//				Log.warn("{}#red for type of {}#red is not implemented yet!", layout, constType);
				layout = Layout.STRIPED;
			}
			
		}else if(layout != Layout.DYNAMIC){
			assert data.filtered(Objects::nonNull).isEmpty();
			layout = Layout.JUST_NULLS;
		}
		
		return new ElementsResult(constType, hasNulls, layout);
	}
	
	public static boolean isTypeCollection(Class<?> typ){
		return typ.isArray() || UtilL.instanceOf(typ, List.class);
	}
}
