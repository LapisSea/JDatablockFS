package com.lapissea.cfs.cluster;

import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.objects.IOType;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

public interface TypeParser{
	
	class Registry implements TypeParser{
		private final List<TypeParser> parsers=new ArrayList<>();
		
		Registry(){ }
		
		public void register(TypeParser parser){
			parsers.add(parser);
		}
		
		@Override
		public boolean canParse(Cluster cluster, IOType type){
			for(TypeParser parser : parsers){
				if(parser.canParse(cluster, type)) return true;
			}
			return false;
		}
		
		@Override
		public UnsafeFunction<Chunk, IOInstance, IOException> parse(Cluster cluster, IOType type){
			for(TypeParser parser : parsers){
				if(parser.canParse(cluster, type)) return parser.parse(cluster, type);
			}
			throw new RuntimeException("Unknown type: "+type);
		}
	}
	
	static TypeParser rawExact(IOStruct rawType){
		if(!rawType.canInstate()) throw new IllegalArgumentException("Struct "+rawType+" is not auto constructable");
		return rawExact(rawType, (cl, typ)->rawType::newInstance);
	}
	
	static TypeParser rawExact(IOStruct rawType, BiFunction<Cluster, IOType, UnsafeFunction<Chunk, IOInstance, IOException>> parser){
		return new TypeParser(){
			@Override
			public boolean canParse(Cluster cluster, IOType type){
				return type.getGenericArgs().isEmpty()&&type.getType().equals(rawType);
			}
			
			@Override
			public UnsafeFunction<Chunk, IOInstance, IOException> parse(Cluster cluster, IOType type){
				assert canParse(cluster, type);
				return parser.apply(cluster, type);
			}
		};
	}
	
	boolean canParse(Cluster cluster, IOType type);
	
	UnsafeFunction<Chunk, IOInstance, IOException> parse(Cluster cluster, IOType type);
	
}
