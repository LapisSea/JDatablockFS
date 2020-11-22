package com.lapissea.cfs.objects;

import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.util.TextUtil;
import com.lapissea.util.ZeroArrays;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.stream.Collectors;

public class IOTypeLayout extends IOInstance implements ParameterizedType{
	
	/*
	 concept tests:
	 
	 
	 test data structure: smart flat pointer array list
	 IOArrayList<ChunkPointer.PtrSmart>(Extract(size))
	
	
	 test data structure: OpenVDB
	 
	 class VoxelData{
	    float density;
	    float temperature;
	    float[4](Precision(16)) color;
	    float[3] vectorMotion;
	    byte materialId;
	 }
	 
	 class Leaf<VoxelData>{
	    boolean active,
	    VoxelData data
	 }
	
	 class Tile<VoxelData>{
	    boolean active,
	    boolean children,
	    boolean[4][4][4] denseActive,
	    Leaf<VoxelData>[4][4][4](Extract(active -> this.denseActive))* leafs
	 }
	
	 class Area<VoxelData>{
	    boolean active,
	    boolean children,
	    Tile<VoxelData>[2][2][2] tiles
	 }
	
	 class Root<VoxelData>{
	    BucketArrayMap<int[3], Tile<VoxelData>> data
	 }
	*/
	
	public abstract static class TypeArgument extends IOInstance{
		public static final TypeArgument[] NO_ARGS=new TypeArgument[0];
		
		@Override
		public String toString(){
			return TextUtil.toNamedJson(this);
		}
	}
	
	@SuppressWarnings("FieldMayBeFinal")
	private static class LayoutData extends IOInstance{
		
		@IOStruct.EnumValue(index=0)
		private NumberSize dimSiz;
		
		@IOStruct.ArrayValue(index=1, rw=AutoRW.class)
		private IOTypeLayout[] generics;
		
		@IOStruct.PrimitiveArrayValue(index=2, sizeRef="dimSiz")
		private int[] arrayDims;
		
		@IOStruct.ArrayValue(index=3, rw=AutoRW.class)
		private TypeArgument[] arguments;
		
		
		public LayoutData(){ }
		public LayoutData(int[] arrayDims, IOTypeLayout[] generics, TypeArgument[] arguments){
			this.arrayDims=arrayDims;
			this.generics=generics;
			this.arguments=arguments;
			dimSiz=NumberSize.bySize(Arrays.stream(this.arrayDims).max().orElse(0));
		}
		
	}
	
	public static final IOTypeLayout[] EMPTY_ARRAY=new IOTypeLayout[0];
	
	@IOStruct.Value(index=0, rw=IOStruct.ClusterDict.class)
	private IOStruct raw;
	
	@IOStruct.Value(index=1, rw=AutoRWEmptyNull.class)
	private LayoutData data;
	
	
	public IOTypeLayout(){ }
	public IOTypeLayout(Class<? extends IOInstance> raw, IOTypeLayout... generics){
		this(raw, null, generics);
	}
	public IOTypeLayout(Class<? extends IOInstance> raw, int[] arrayDims, IOTypeLayout... generics){
		this(raw, arrayDims, generics, null);
	}
	public IOTypeLayout(Class<? extends IOInstance> raw, int[] arrayDims, IOTypeLayout[] generics, TypeArgument[] arguments){
		this(IOStruct.get(raw), arrayDims, generics, arguments);
	}
	public IOTypeLayout(IOStruct raw, IOTypeLayout... generics){
		this(raw, null, generics);
	}
	public IOTypeLayout(IOStruct raw, int[] arrayDims, IOTypeLayout... generics){
		this(raw, arrayDims, generics, null);
	}
	public IOTypeLayout(IOStruct raw, int[] arrayDims, IOTypeLayout[] generics, TypeArgument[] arguments){
		this.raw=raw;
		
		var arrayDimsNN=arrayDims==null?ZeroArrays.ZERO_INT:arrayDims;
		var argumentsNN=arguments==null?TypeArgument.NO_ARGS:arguments;
		var genericsNN =generics==null?EMPTY_ARRAY:generics;
		
		if(arrayDimsNN.length+genericsNN.length+argumentsNN.length>0){
			data=new LayoutData(arrayDimsNN, genericsNN, argumentsNN);
		}
	}
	
	
	public int[] getArrayDims(){
		return data==null?ZeroArrays.ZERO_INT:data.arrayDims;
	}
	public TypeArgument[] getArguments(){
		return data==null?TypeArgument.NO_ARGS:data.arguments;
	}
	
	@Override
	public IOTypeLayout[] getActualTypeArguments(){ return data==null?EMPTY_ARRAY:data.generics; }
	@Override
	public IOStruct getRawType(){ return raw; }
	
	@Deprecated
	@Override
	public Type getOwnerType(){ return null; }
	
	@Override
	public String toString(){ return toString(false); }
	public String toShortString(){ return toString(true); }
	
	private String toString(boolean smal){
		StringBuilder result=new StringBuilder();
		result.append(smal?raw.instanceClass.getSimpleName():raw.getTypeName());
		
		if(getActualTypeArguments().length>0){
			result.append(Arrays.stream(getActualTypeArguments())
			                    .map(e->e.toString(smal))
			                    .collect(Collectors.joining(", ", "<", ">")));
		}
		
		if(getArrayDims().length>0){
			result.append(Arrays.stream(getArrayDims())
			                    .mapToObj(dim->'['+(dim==-1?"":""+dim)+']')
			                    .collect(Collectors.joining()));
		}
		
		if(getArguments().length>0){
			result.append(Arrays.stream(getArguments())
			                    .map(TypeArgument::toString)
			                    .collect(Collectors.joining(", ", "(", ")")));
		}
		return result.toString();
		
	}
}
