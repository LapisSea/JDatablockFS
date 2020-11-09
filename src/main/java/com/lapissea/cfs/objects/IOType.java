package com.lapissea.cfs.objects;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.ReaderWriter;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.IOStruct.Construct;
import com.lapissea.cfs.io.struct.IOStruct.Get;
import com.lapissea.cfs.io.struct.IOStruct.Set;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ObjectPointer;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.util.ArrayViewList;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class IOType extends IOInstance{
	
	public static class Dictionary extends IOInstance.Contained.SingletonChunk<Dictionary>{
		
		@IOStruct.PointerValue(index=0, type=StructLinkedList.class, rw=ObjectPointer.AutoSizedNoOffsetIO.class)
		private IOList<String> typeNames;
		
		@IOStruct.PointerValue(index=1, type=StructLinkedList.class, rw=ObjectPointer.AutoSizedNoOffsetIO.class)
		private IOList<IOType> registeredTypes;
		
		private IOList<IOType> registeredTypesView;
		
		private final Chunk container;
		
		public Dictionary(Chunk container){
			this.container=container;
		}
		
		public IOList<IOType> getRegisteredTypes(){
			return registeredTypesView;
		}
		
		public void initData() throws IOException{
			assert typeNames==null;
			initPointerVarAll(getContainer().cluster);
		}
		
		@Get
		private IOList<AutoText> getTypeNames(){
			return IOList.unbox(typeNames);
		}
		
		@Set
		private void setTypeNames(IOList<AutoText> typeNames){
			this.typeNames=IOList.box(typeNames, AutoText::getData, AutoText::new);
		}
		
		@Set
		private void setRegisteredTypes(IOList<IOType> registeredTypes){
			this.registeredTypes=registeredTypes;
			registeredTypesView=IOList.readOnly(registeredTypes);
		}
		
		@Construct
		private IOList<AutoText> constructTypeNames(Chunk source) throws IOException{
			return StructLinkedList.build(b->b.withContainer(source)
			                                  .withElementConstructor(AutoText::new)
			                                  .withSolidNodes(true));
		}
		
		@Construct
		private IOList<IOType> constructRegisteredTypes(Chunk source) throws IOException{
			return StructLinkedList.build(b->b.withContainer(source)
			                                  .withElementConstructor(()->new IOType(this))
			                                  .withSolidNodes(true));
		}
		
		@Override
		public Chunk getContainer(){
			return container;
		}
		
		public void addType(IOType type) throws IOException{
			if(type.dictionary!=null){
				if(type.dictionary==this) return;
				throw new IllegalArgumentException(type+" was already registered somewhere else");
			}
			type.bindDictionary(this);
			if(registeredTypes.contains(type)) return;
			
			registeredTypes.addElement(type);
		}
		
		@Override
		public String toString(){
			return registeredTypes.toString();
		}
		
		public void validate() throws IOException{
			typeNames.validate();
			registeredTypes.validate();
		}
	}
	
	
	public static class IndexRef implements ReaderWriter<IOType>{
		
		@Override
		public IOType read(Object targetObj, Cluster cluster, ContentReader source, IOType oldValue) throws IOException{
			return cluster.getTypeDictionary().getRegisteredTypes().getElement((int)NumberSize.INT.read(source));
		}
		
		@Override
		public void write(Object targetObj, Cluster cluster, ContentWriter target, IOType source) throws IOException{
			var index=source.getIndex();
			assert index!=-1;
			NumberSize.INT.write(target, index);
		}
		
		@Override
		public long mapSize(Object targetObj, IOType source){
			return NumberSize.INT.bytes;
		}
		
		@Override
		public OptionalInt getFixedSize(){
			return NumberSize.INT.optionalBytes;
		}
		
		@Override
		public OptionalInt getMaxSize(){
			return NumberSize.INT.optionalBytes;
		}
	}
	
	private static final NumberSize STRING_INDEX_SIZE=NumberSize.SMALL_INT;
	
	@IOStruct.Value(index=0)
	private IOStruct type;
	
	@IOStruct.Value(index=1)
	private List<IOType> genericArgs;
	
	private int index=-1;
	
	private Dictionary dictionary;
	
	private IOType(Dictionary dictionary){
		this.dictionary=dictionary;
	}
	
	@SafeVarargs
	public IOType(Class<? extends IOInstance> type, Class<? extends IOInstance> arg, Class<? extends IOInstance>... genericArgs){
		this(type, Stream.concat(Stream.of(arg), Arrays.stream(genericArgs)).map(IOType::new).toArray(IOType[]::new));
	}
	
	public IOType(Class<? extends IOInstance> type, Class<? extends IOInstance> arg){
		this(type, new IOType(arg));
	}
	
	public IOType(Class<? extends IOInstance> type, IOType... genericArgs){
		this(IOStruct.get(type), genericArgs);
	}
	
	public IOType(IOStruct type, IOType... genericArgs){
		Objects.requireNonNull(type);
		this.type=type;
		this.genericArgs=List.of(genericArgs);
	}
	
	
	private void bindDictionary(Dictionary dictionary){
		this.dictionary=dictionary;
		for(IOType arg : genericArgs){
			arg.bindDictionary(dictionary);
		}
	}
	
	private String readString(ContentReader source) throws IOException{
		int stringId=(int)STRING_INDEX_SIZE.read(source);
		return dictionary.typeNames.getElement(stringId);
	}
	
	private void writeString(ContentWriter source, String string) throws IOException{
		int stringId=dictionary.typeNames.indexOf(string);
		if(stringId==-1){
			dictionary.typeNames.addElement(string);
			stringId=dictionary.typeNames.indexOf(string);
		}
		STRING_INDEX_SIZE.write(source, stringId);
	}
	
	private long sizeString(String string){
		return STRING_INDEX_SIZE.bytes;
	}
	
	private long sizeNum(int num){
		return NumberSize.SMALEST_REAL.bytes+NumberSize.bySize(num).bytes;
	}
	
	private void writeNum(ContentWriter target, int source) throws IOException{
		NumberSize dataSize=NumberSize.bySize(source);
		
		try(var flags=new FlagWriter.AutoPop(NumberSize.SMALEST_REAL, target)){
			flags.writeEnum(NumberSize.FLAG_INFO, dataSize);
		}
		
		dataSize.write(target, source);
	}
	
	private int readNum(ContentReader source) throws IOException{
		NumberSize dataSize=FlagReader.readSingle(source, NumberSize.SMALEST_REAL, NumberSize.FLAG_INFO);
		return (int)dataSize.read(source);
	}
	
	private String structToString(IOStruct struct){
		return struct.instanceClass.getName();
	}
	
	private IOStruct stringToStruct(String string){
		Class<? extends IOInstance> typeC;
		try{
			//noinspection unchecked
			typeC=(Class<? extends IOInstance>)Class.forName(string);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
		return IOStruct.get(typeC);
	}
	
	
	@IOStruct.Read
	private IOStruct readType(Cluster cluster, ContentReader source, IOStruct oldValue) throws IOException{
		return stringToStruct(readString(source));
	}
	
	@IOStruct.Write
	private void writeType(Cluster cluster, ContentWriter dest, IOStruct value) throws IOException{
		writeString(dest, structToString(value));
	}
	
	@IOStruct.Size
	private long sizeType(IOStruct value){
		return sizeString(value.instanceClass.getName());
	}
	
	
	@IOStruct.Read
	private List<IOType> readGenericArgs(Cluster cluster, ContentReader source, List<IOType> oldValue) throws IOException{
		var args=new IOType[readNum(source)];
		for(int i=0;i<args.length;i++){
			var typ=new IOType(dictionary);
			typ.readStruct(null, source);
			args[i]=typ;
		}
		return ArrayViewList.create(args).obj2;
	}
	
	@IOStruct.Write
	private void writeGenericArgs(Cluster cluster, ContentWriter dest, List<IOType> value) throws IOException{
		writeNum(dest, value.size());
		for(IOType arg : value){
			arg.writeStruct(null, dest);
		}
	}
	
	@IOStruct.Size
	private long sizeGenericArgs(List<IOType> genericArgs){
		return sizeNum(genericArgs.size())+
		       genericArgs.stream().mapToLong(IOInstance::getInstanceSize).sum();
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		return o instanceof IOType ioType&&
		       type.equals(ioType.type)&&
		       Objects.equals(dictionary, ioType.dictionary)&&
		       Objects.equals(genericArgs, ioType.genericArgs);
	}
	
	@Override
	public int hashCode(){
		int result=type.hashCode();
		result=31*result+genericArgs.hashCode();
		return result;
	}
	
	@Override
	public String toString(){
		return type.instanceClass.getName()+(genericArgs.isEmpty()?"":genericArgs.stream().map(IOType::toString).collect(Collectors.joining(", ", "<", ">")));
	}
	
	public String toShortString(){
		var name=type.instanceClass.getName();
		return name.substring(name.lastIndexOf('.')+1)+(genericArgs.isEmpty()?"":genericArgs.stream().map(IOType::toShortString).collect(Collectors.joining(", ", "<", ">")));
	}
	
	public IOStruct getType(){
		return type;
	}
	
	public int getIndex() throws IOException{
		if(index==-1&&dictionary!=null){
			index=dictionary.getRegisteredTypes().indexOf(this);
		}
		return index;
	}
	
	public List<IOType> getGenericArgs(){
		return genericArgs;
	}
}
