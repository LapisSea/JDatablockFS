package com.lapissea.cfs.chunk;

import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.objects.collections.AbstractUnmanagedIOMap;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.NotImplementedException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public sealed interface DataPool{
	
	final class InMemory implements DataPool{
		
		private static final class Data<T>{
			private final List<T>             index      = new ArrayList<>();
			private final Map<Integer, int[]> hashLookup = new HashMap<>();
		}
		
		
		private final Map<String, Data<?>> data = new HashMap<>();
		
		@Override
		public <T> int toId(Class<T> type, T val, boolean write){
			if(val == null) return -1;
			
			//noinspection unchecked
			var n          = (Data<T>)data.computeIfAbsent(type.getName(), t -> new Data<>());
			var hashLookup = n.hashLookup;
			var index      = n.index;
			
			Integer hash = val.hashCode();
			
			var ids = hashLookup.get(hash);
			if(ids != null){
				for(int id : ids){
					if(index.get(id).equals(val)){
						return id;
					}
				}
			}
			if(ids == null) ids = new int[0];
			
			var l = ids.length;
			ids = Arrays.copyOf(ids, l + 1);
			
			var id = index.size();
			//noinspection unchecked
			index.add(val instanceof IOInstance<?> i? (T)i.clone() : val);
			
			ids[l] = id;
			hashLookup.put(hash, ids);
			
			return id;
		}
		
		@Override
		public <T> T fromId(Class<T> type, int id){
			//noinspection unchecked
			var n = (Data<T>)data.get(type.getName());
			if(n == null) return null;
			var index = n.index;
			return index.get(id);
		}
	}
	
	final class Persistent implements DataPool{
		
		@IOValue
		static final class TypeData<T> extends IOInstance.Managed<TypeData<T>>{
			private IOList<T>             index;
			private IOMap<Integer, int[]> hashLookup;
			
			public TypeData(){ }
		}
		
		static{
			//noinspection unchecked
			Thread.startVirtualThread(() -> StandardStructPipe.of(TypeData.class));
		}
		
		private final AbstractUnmanagedIOMap<String, TypeData<?>> typeMap;
		
		Persistent(AbstractUnmanagedIOMap<String, TypeData<?>> typeMap){
			this.typeMap = typeMap;
		}
		
		private <T> TypeData<T> getData(Class<T> type, boolean write) throws IOException{
			var name = type == String.class? "$STR" : type.getName();
			
			//noinspection unchecked
			var data = (TypeData<T>)typeMap.get(name);
			if(data == null){
				if(!write) return null;
				data = new TypeData<>();
				var ctx = data.getThisStruct().describeGenerics(TypeLink.of(TypeData.class, type));
				data.allocateNulls(typeMap.getDataProvider(), ctx);
				typeMap.put(name, data);
			}
			return data;
		}
		
		private int strToId(String val, boolean write) throws IOException{
			TypeData<String> data = getData(String.class, write);
			if(data == null) return -1;
			
			var hashLookup  = data.hashLookup;
			var stringIndex = data.index;
			
			Integer hash = val.hashCode();
			
			var ids = hashLookup.get(hash);
			if(ids != null){
				for(int i : ids){
					var str = stringIndex.get(i);
					if(str.equals(val)){
						return i;
					}
				}
			}
			
			var idx = Math.toIntExact(stringIndex.size());
			stringIndex.add(val);
			
			var arr = hashLookup.get(hash);
			if(arr == null) arr = new int[0];
			
			var l = arr.length;
			arr = Arrays.copyOf(arr, l + 1);
			arr[l] = idx;
			
			hashLookup.put(hash, arr);
			return idx;
		}
		private String strFromId(int id) throws IOException{
			var data = getData(String.class, false);
			if(data == null) return null;
			var stringIndex = data.index;
			return stringIndex.get(id);
		}
		
		
		@Override
		public <T> int toId(Class<T> type, T val, boolean write) throws IOException{
			if(type == String.class){
				return strToId((String)val, write);
			}
			
			throw new NotImplementedException();
		}
		@Override
		public <T> T fromId(Class<T> type, int id) throws IOException{
			if(type == String.class){
				//noinspection unchecked
				return (T)strFromId(id);
			}
			
			throw new NotImplementedException();
		}
	}
	
	<T> int toId(Class<T> type, T val, boolean write) throws IOException;
	<T> T fromId(Class<T> type, int id) throws IOException;
}
