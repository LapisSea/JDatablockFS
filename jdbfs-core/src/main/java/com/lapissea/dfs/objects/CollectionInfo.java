package com.lapissea.dfs.objects;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.IterablePP;
import com.lapissea.dfs.utils.IterablePPs;
import com.lapissea.util.NotImplementedException;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

@IOValue
public class CollectionInfo extends IOInstance.Managed<CollectionInfo>{
	private enum CollectionType{
		NULL,
		ARRAY,
		ARRAY_LIST,
		UNMODIFIABLE_LIST,
		//HASH_SET, maybe??
	}
	
	private enum Layout{
		DYNAMIC,
		STRIPED,
		STRUCT_OF_ARRAYS
	}
	
	private record AnalysisResult(CollectionType type, Layout layout, boolean hasNulls){ }
	
	private static final Set<Class<?>> UNMODIFIABLE_LISTS;
	
	public static final StructPipe<CollectionInfo> PIPE = StandardStructPipe.of(CollectionInfo.class);
	
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
	
	private CollectionType collectionType;
	@IOValue.Unsigned
	@IODependency.VirtualNumSize
	private int            length;
	private Layout         layout;
	@IODependency.VirtualNumSize
	@IOValue.Unsigned
	private int            typeID;
	private boolean        hasNullElements;
	
	public CollectionInfo(){ }
	public CollectionInfo(Object collection){
		var res = analyze(collection);
		layout = res.layout;
		collectionType = res.type;
		hasNullElements = res.hasNulls;
		length = length(collectionType, collection);
	}
	
	private static AnalysisResult analyze(Object collection){
		if(collection == null){
			return new AnalysisResult(CollectionType.NULL, Layout.STRIPED, false);
		}
		
		final var type = switch(collection){
			case List<?> l -> {
				if(l instanceof ArrayList<?>) yield CollectionType.ARRAY_LIST;
				if(UNMODIFIABLE_LISTS.contains(collection.getClass())){
					yield CollectionType.UNMODIFIABLE_LIST;
				}
				throw new IllegalArgumentException(collection.getClass().getName() + " is not a supported array type");
			}
			default -> {
				if(collection.getClass().isArray()){
					yield CollectionType.ARRAY;
				}
				throw new IllegalArgumentException(collection.getClass().getName() + " is not a supported collection type");
			}
		};
		
		if(type == CollectionType.ARRAY){
			var componentType = collection.getClass().componentType();
			if(componentType.isPrimitive()){
				return new AnalysisResult(type, Layout.STRIPED, false);
			}
		}
		
		Class<?> typ      = null;
		boolean  hasNulls = false;
		var      layout   = Layout.STRIPED;
		
		for(var el : iter(type, collection)){
			if(el == null){
				hasNulls = true;
				continue;
			}
			var eTyp = el.getClass();
			if(layout != Layout.DYNAMIC){
				if(typ == null) typ = eTyp;
				else if(typ != eTyp){
					layout = Layout.DYNAMIC;
				}
			}
		}
		
		if(typ != null && layout != Layout.DYNAMIC){
			layout = Struct.tryOf(typ).filter(s -> !(s instanceof Struct.Unmanaged)).map(s -> {
				return switch(IOFieldTools.minWordSpace(s.getFields())){
					case BIT -> Layout.STRUCT_OF_ARRAYS;
					case BYTE -> Layout.STRIPED;
				};
			}).orElse(layout);
		}
		
		return new AnalysisResult(type, layout, hasNulls);
	}
	
	private static IterablePP<?> iter(CollectionType type, Object collection){
		return switch(type){
			case NULL -> IterablePPs.of();
			case ARRAY -> IterablePPs.rangeMap(0, Array.getLength(collection), (int i) -> Array.get(collection, i));
			case ARRAY_LIST, UNMODIFIABLE_LIST -> IterablePPs.of((Collection<?>)collection);
		};
	}
	private static int length(CollectionType type, Object collection){
		return switch(type){
			case NULL -> 0;
			case ARRAY -> Array.getLength(collection);
			case ARRAY_LIST, UNMODIFIABLE_LIST -> ((Collection<?>)collection).size();
		};
	}
	
	
	public Object readCollection(DataProvider provider, ContentReader data){
		Objects.requireNonNull(collectionType, "CollectionInfo not initialized");
		return switch(collectionType){
			case NULL -> null;
			case ARRAY -> {
				throw new NotImplementedException();//TODO
			}
			case ARRAY_LIST, UNMODIFIABLE_LIST -> {
				var l = new ArrayList<>(length);
				for(int i = 0; i<length; i++){
					l.add(readElement(provider, data));
				}
				
				if(collectionType == CollectionType.UNMODIFIABLE_LIST){
					yield List.copyOf(l);
				}
				yield l;
			}
		};
	}
	
	private Object readElement(DataProvider provider, ContentReader data){
		throw new NotImplementedException();//TODO
	}
	
	@Override
	public String toString(){
		if(collectionType == null) return CollectionInfo.class.getSimpleName() + "{<UNINITIALIZED>}";
		var res = new StringJoiner(", ", CollectionInfo.class.getSimpleName() + "{", "}");
		res.add("type: " + collectionType);
		res.add("len: " + length);
		res.add("layout: " + layout);
		if(layout != Layout.DYNAMIC) res.add("typeID: " + typeID);
		return res.toString();
	}
}
