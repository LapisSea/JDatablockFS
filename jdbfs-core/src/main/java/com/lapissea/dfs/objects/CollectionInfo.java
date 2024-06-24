package com.lapissea.dfs.objects;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOUnsafeValue;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.IterablePP;
import com.lapissea.dfs.utils.IterablePPs;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.util.List;
import java.util.function.Function;

import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;

public sealed interface CollectionInfo{
	
	enum Layout{
		DYNAMIC,
		STRIPED,
		STRUCT_OF_ARRAYS,
		JUST_NULLS
	}
	
	interface Store extends IOInstance.Def<Store>{
		
		CollectionInfo val();
		
		StructPipe<Store> PIPE = StandardStructPipe.of(Store.class);
		static Store of(CollectionInfo val){
			class Cache{
				private static final Function<CollectionInfo, Store> make = Def.constrRef(Store.class, CollectionInfo.class);
			}
			return Cache.make.apply(val);
		}
	}
	
	@IOValue
	final class NullValue extends IOInstance.Managed<NullValue> implements CollectionInfo{
		
		static final NullValue INSTANCE = new NullValue();
		
		@Override
		public Type constantType(){ return null; }
		@Override
		public int length(){ return 0; }
		@Override
		public Layout layout(){ return Layout.STRIPED; }
		@Override
		public boolean hasNulls(){ return false; }
		@Override
		public IterablePP<?> iter(Object collection){ return IterablePPs.of(); }
	}
	
	@IOValue
	final class PrimitiveArrayInfo extends IOInstance.Managed<PrimitiveArrayInfo> implements CollectionInfo{
		
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		private int length;
		
		@IOUnsafeValue
		private Class<?> elementType;
		
		public PrimitiveArrayInfo(){ }
		public PrimitiveArrayInfo(int length, Class<?> elementType){
			this.length = length;
			this.elementType = elementType;
		}
		
		@Override
		public Class<?> constantType(){ return elementType; }
		@Override
		public int length(){ return length; }
		@Override
		public Layout layout(){ return Layout.STRUCT_OF_ARRAYS; }
		@Override
		public boolean hasNulls(){ return false; }
		@Override
		public IterablePP<?> iter(Object collection){
			return IterablePPs.rangeMap(0, Array.getLength(collection), i -> Array.get(collection, i));
		}
	}
	
	@IOValue
	final class ArrayInfo extends IOInstance.Managed<ArrayInfo> implements CollectionInfo{
		
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		private int     length;
		@IONullability(NULLABLE)
		@IOUnsafeValue
		private Type    constantType;
		private Layout  layout;
		private boolean hasNulls;
		
		@IOUnsafeValue
		private Class<?> arrayType;
		
		public ArrayInfo(){ }
		public ArrayInfo(Class<?> arrayType, int length, Type constantType, Layout layout, boolean hasNulls){
			this.length = length;
			this.constantType = constantType;
			this.layout = layout;
			this.hasNulls = hasNulls;
			this.arrayType = arrayType;
		}
		
		@Override
		public Type constantType(){ return constantType; }
		@Override
		public int length(){ return length; }
		@Override
		public Layout layout(){ return layout; }
		@Override
		public boolean hasNulls(){ return hasNulls; }
		@Override
		public IterablePP<?> iter(Object collection){
			var arr = (Object[])collection;
			return IterablePPs.rangeMap(0, arr.length, i -> arr[i]);
		}
		
		public Class<?> getArrayType(){ return arrayType; }
		
	}
	
	@IOValue
	final class ListInfo extends IOInstance.Managed<ListInfo> implements CollectionInfo{
		
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		private int     length;
		@IONullability(NULLABLE)
		@IOUnsafeValue
		private Type    constantType;
		private Layout  layout;
		private boolean hasNulls;
		
		private boolean unmodifiable;
		
		public ListInfo(){ }
		public ListInfo(int length, Type constantType, Layout layout, boolean hasNulls, boolean unmodifiable){
			this.length = length;
			this.constantType = constantType;
			this.layout = layout;
			this.hasNulls = hasNulls;
			this.unmodifiable = unmodifiable;
		}
		
		@Override
		public Type constantType(){ return constantType; }
		@Override
		public int length(){ return length; }
		@Override
		public Layout layout(){ return layout; }
		@Override
		public boolean hasNulls(){ return hasNulls; }
		
		public boolean isUnmodifiable(){ return unmodifiable; }
		
		@Override
		public IterablePP<?> iter(Object collection){
			//noinspection unchecked
			var list = (List<Object>)collection;
			return list::iterator;
		}
	}
	
	Type constantType();
	int length();
	Layout layout();
	boolean hasNulls();
	
	IterablePP<?> iter(Object collection);
	
	default long calcIOBytes(DataProvider provider){
		return Store.PIPE.calcUnknownSize(provider, Store.of(this), WordSpace.BYTE);
	}
	
	default void write(DataProvider provider, ContentWriter dest) throws IOException{
		Store.PIPE.write(provider, dest, Store.of(this));
	}
	static CollectionInfo read(DataProvider provider, ContentReader src) throws IOException{
		return Store.PIPE.readNew(provider, src, null).val();
	}
}
