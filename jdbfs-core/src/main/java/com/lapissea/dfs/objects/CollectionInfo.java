package com.lapissea.dfs.objects;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.IterablePP;
import com.lapissea.dfs.utils.IterablePPs;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Type;
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
public final class CollectionInfo extends IOInstance.Managed<CollectionInfo>{
	public enum CollectionType{
		NULL,
		ARRAY,
		ARRAY_LIST,
		UNMODIFIABLE_LIST,
		//HASH_SET, maybe??
	}
	
	public enum Layout{
		DYNAMIC,
		STRIPED,
		STRUCT_OF_ARRAYS,
		NO_VALUES
	}
	
	public record AnalysisResult(CollectionType type, Layout layout, boolean hasNulls, Class<?> constantType, int length){ }
	
	private static final Set<Class<?>> UNMODIFIABLE_LISTS;
	
	public static final StructPipe<CollectionInfo> PIPE = StandardStructPipe.of(CollectionInfo.class);
	
	public static boolean isTypeCollection(Class<?> typ){
		return typ.isArray() || UtilL.instanceOf(typ, List.class);
	}
	
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
	private boolean        hasNullElements;
	
	public CollectionInfo(){ }
	public CollectionInfo(Object collection){
		this(analyze(collection));
	}
	public CollectionInfo(AnalysisResult analysis){
		if(analysis == null) throw new IllegalArgumentException("Not a collection");
		layout = analysis.layout;
		collectionType = analysis.type;
		hasNullElements = analysis.hasNulls;
		length = analysis.length;
	}
	
	public static AnalysisResult analyze(Object collection){
		if(collection == null){
			return new AnalysisResult(CollectionType.NULL, Layout.STRIPED, false, null, 0);
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
				yield null;
			}
		};
		if(type == null) return null;
		
		if(type == CollectionType.ARRAY){
			var componentType = collection.getClass().componentType();
			if(componentType.isPrimitive()){
				return new AnalysisResult(type, Layout.STRIPED, false, componentType, Array.getLength(collection));
			}
		}
		
		Class<?> constType = null;
		boolean  hasNulls  = false;
		var      layout    = Layout.STRIPED;
		boolean  uModList  = false;
		
		for(var el : iter(type, collection)){
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
				Log.warn("{}#red for type of {}#red is not implemented yet!", layout, constType);
				layout = Layout.STRIPED;
			}
			
		}else if(layout != Layout.DYNAMIC){
			assert iter(type, collection).filtered(Objects::nonNull).isEmpty();
			layout = Layout.NO_VALUES;
		}
		
		return new AnalysisResult(type, layout, hasNulls, constType, length(type, collection));
	}
	
	public static IterablePP<?> iter(CollectionType type, Object collection){
		return switch(type){
			case NULL -> IterablePPs.of();
			case ARRAY -> {
				if(collection.getClass().getComponentType().isPrimitive()){
					yield IterablePPs.rangeMap(0, Array.getLength(collection), (int i) -> Array.get(collection, i));
				}
				var arr = (Object[])collection;
				yield IterablePPs.rangeMap(0, arr.length, (int i) -> arr[i]);
			}
			case ARRAY_LIST, UNMODIFIABLE_LIST -> IterablePPs.of((Collection<?>)collection);
		};
	}
	public static int length(CollectionType type, Object collection){
		return switch(type){
			case NULL -> 0;
			case ARRAY -> Array.getLength(collection);
			case ARRAY_LIST, UNMODIFIABLE_LIST -> ((Collection<?>)collection).size();
		};
	}
	
	@Override
	public String toString(){
		if(collectionType == null) return CollectionInfo.class.getSimpleName() + "{<UNINITIALIZED>}";
		var res = new StringJoiner(", ", CollectionInfo.class.getSimpleName() + "{", "}");
		res.add("type: " + collectionType);
		res.add("len: " + length);
		res.add("layout: " + layout);
		if(hasNullElements) res.add("nulls");
		return res.toString();
	}
	
	public int calcIOBytes(){
		return (int)PIPE.calcUnknownSize(null, this, WordSpace.BYTE);
	}
	
	public void write(ContentWriter dest) throws IOException{
		PIPE.write((DataProvider)null, dest, this);
	}
	
	public static CollectionInfo read(ContentReader src) throws IOException{
		return PIPE.readNew(null, src, null);
	}
	
	public Type getComponent(Type type){
		return switch(collectionType){
			case NULL -> Object.class;
			case ARRAY -> ((Class<?>)type).getComponentType();
			case ARRAY_LIST, UNMODIFIABLE_LIST -> null;
		};
	}
	
	public int length()                   { return length; }
	public Layout layout()                { return layout; }
	public CollectionType collectionType(){ return collectionType; }
	public boolean hasNullElements()      { return hasNullElements; }
}
