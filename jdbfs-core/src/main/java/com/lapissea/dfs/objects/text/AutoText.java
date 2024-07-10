package com.lapissea.dfs.objects.text;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.bit.EnumUniverse;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputStream;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.instancepipe.ObjectPipe;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.objects.text.Encoding.CharEncoding;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.BasicSizeDescriptor;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.access.VirtualAccessor;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.reflection.BitFieldMerger;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.Objects;
import java.util.OptionalLong;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

@StructPipe.Special
public final class AutoText extends IOInstance.Managed<AutoText> implements CharSequence{
	
	public static final Struct<AutoText> STRUCT = Struct.of(AutoText.class);
	
	private static final class AutoTextPipe extends StandardStructPipe<AutoText>{
		public AutoTextPipe(){
			super(STRUCT, true);
		}
		
		private static final EnumUniverse<CharEncoding> CHAR_ENCODING_UNIVERSE = EnumUniverse.of(CharEncoding.class);
		
		static{
			if(CHAR_ENCODING_UNIVERSE.bitSize != 3) throw new AssertionError();
		}
		
		private static final VirtualAccessor<AutoText>
			NUM_SIZE = (VirtualAccessor<AutoText>)STRUCT.getFields().requireExact(NumberSize.class, "numSize").getAccessor(),
			TEXT_LEN = (VirtualAccessor<AutoText>)STRUCT.getFields().requireExact(int.class, "textBytes:len").getAccessor();
		
		@Override
		protected AutoText doRead(VarPool<AutoText> ioPool, DataProvider provider, ContentReader src, AutoText instance, GenericContext g) throws IOException{
			var raw     = src.readUnsignedInt1();
			var numSize = NumberSize.FLAG_INFO.get(raw&0b111);
			ioPool.set(NUM_SIZE, numSize);
			instance.setEncoding(CHAR_ENCODING_UNIVERSE.get((raw >>> 3)&0b111));
			
			BitFieldMerger.readIntegrityBits(raw, 8, 6);
			
			var textBytes_len = numSize.readInt(src);
			ioPool.setInt(TEXT_LEN, textBytes_len);
			
			instance.setCharCount(numSize.readInt(src));
			instance.setTextBytes(src.readInts1(textBytes_len));
			return instance;
		}
		@Override
		protected void doWrite(DataProvider provider, ContentWriter dest, VarPool<AutoText> ioPool, AutoText value) throws IOException{
			int    charCount     = value.charCount;
			byte[] textBytes     = value.getTextBytes();
			int    textBytes_len = textBytes.length;
			
			var numSize = NumberSize.bySize(Math.max(charCount, textBytes_len));
			
			var data = numSize.ordinal()|(value.encoding.ordinal()<<3);
			data |= ((int)BitFieldMerger.calcIntegrityBits(data, 2, 6));
			dest.writeInt1(data);
			
			numSize.writeInt(dest, textBytes_len);
			numSize.writeInt(dest, charCount);
			dest.writeInts1(textBytes);
		}
		
		@Override
		public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
			var raw     = src.readUnsignedInt1();
			var numSize = NumberSize.FLAG_INFO.get(raw&0b111);
			BitFieldMerger.readIntegrityBits(raw, 8, 6);
			
			var textBytes_len = numSize.readInt(src);
			
			src.skip(numSize.bytes + textBytes_len);
		}
		
		@Override
		protected SizeDescriptor<AutoText> createSizeDescriptor(){
			return SizeDescriptor.UnknownLambda.of(1, OptionalLong.empty(), (ioPool, prov, value) -> {
				var charCount = value.charCount;
				int textBytesCount;
				if(value.dataSrc != null) textBytesCount = value.dataSrc.length;
				else textBytesCount = value.encoding.calcSize(value.data);
				
				var numSize = NumberSize.bySize(Math.max(charCount, textBytesCount));
				
				return 1L + numSize.bytes + numSize.bytes + textBytesCount;
			});
		}
	}
	
	static{
		if(ConfigDefs.OPTIMIZED_PIPE.resolveVal()){
			StandardStructPipe.registerSpecialImpl(STRUCT, AutoTextPipe::new);
		}
	}
	
	public static final StructPipe<AutoText> PIPE = StandardStructPipe.of(STRUCT);
	
	static{
		if(DEBUG_VALIDATION){
			var check = "numSize + encoding 1 byte textBytes:len NS(numSize): 0-4 bytes charCount NS(numSize): 0-4 bytes textBytes ? bytes ";
			var sb    = new StringBuilder(check.length());
			STRUCT.waitForState(Struct.STATE_INIT_FIELDS);
			var p = StandardStructPipe.of(STRUCT);
			for(var specificField : p.getSpecificFields()){
				sb.append(specificField.getName()).append(' ').append(specificField.getSizeDescriptor()).append(' ');
			}
			var res = sb.toString();
			if(!check.equals(res)){
				throw UtilL.exitWithErrorMsg(check + "\n" + res);
			}
		}
	}
	
	public static final ObjectPipe.NoPool<String> STR_PIPE = new ObjectPipe.NoPool<>(){
		private final BasicSizeDescriptor<String, Void> sizeDescriptor;
		
		{
			var desc = AutoText.PIPE.getSizeDescriptor();
			sizeDescriptor = BasicSizeDescriptor.Unknown.of(
				desc.getWordSpace(), desc.getMin(), desc.getMax(),
				(pool, prov, value) -> desc.calcUnknown(null, null, new AutoText(value), desc.getWordSpace()));
		}
		
		@Override
		public void write(DataProvider provider, ContentWriter dest, String instance) throws IOException{
			AutoText.PIPE.write(provider, dest, new AutoText(instance));
		}
		@Override
		public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
			AutoText.PIPE.skip(provider, src, null);
		}
		@Override
		public String readNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
			return AutoText.PIPE.readNew(provider, src, null).getData();
		}
		@Override
		public BasicSizeDescriptor<String, Void> getSizeDescriptor(){ return sizeDescriptor; }
	};
	
	private String       data;
	private byte[]       dataSrc;
	@IOValue
	private CharEncoding encoding;
	@IOValue
	@IOValue.Unsigned
	@IODependency.VirtualNumSize(name = "numSize")
	private int          charCount;
	
	
	public AutoText(){
		super(STRUCT);
		data = "";
		encoding = CharEncoding.DEFAULT;
		charCount = 0;
	}
	
	public AutoText(String data){
		setData(data);
	}
	
	public void setData(@NotNull String newData){
		Objects.requireNonNull(newData);
		
		encoding = CharEncoding.findBest(newData);
		charCount = newData.length();
		data = newData;
		dataSrc = null;
	}
	
	@IOValue
	private void setCharCount(int charCount){
		this.charCount = charCount;
		dataSrc = null;
	}
	@IOValue
	private void setEncoding(CharEncoding encoding){
		this.encoding = encoding;
		dataSrc = null;
	}
	
	@IOValue
	@IODependency({"charCount", "encoding"})
	@IODependency.ArrayLenSize(name = "numSize")
	private byte[] getTextBytes() throws IOException{
		if(dataSrc == null){
			dataSrc = generateBytes();
		}
		return dataSrc;
	}
	
	private byte[] generateBytes() throws IOException{
		byte[] buff = new byte[encoding.calcSize(data)];
		writeTextBytes(new ContentOutputStream.BA(buff));
		return buff;
	}
	
	@IOValue
	private void setTextBytes(byte[] bytes) throws IOException{
		dataSrc = bytes;
		StringBuilder sb = new StringBuilder(charCount);
		readTextBytes(new ContentInputStream.BA(bytes), sb);
		data = sb.toString();
	}
	
	public void writeTextBytes(ContentWriter dest) throws IOException{
		encoding.write(dest, data);
	}
	public void readTextBytes(ContentInputStream src, StringBuilder dest) throws IOException{
		encoding.read(src, charCount, dest);
	}
	
	
	@NotNull
	@Override
	public String toString(){
		return data;
//		return set+": "+data;
	}
	
	@Override
	public int length(){
		return charCount;
	}
	@Override
	public CharSequence subSequence(int start, int end){
		return data.subSequence(start, end);
	}
	@Override
	public char charAt(int index){
		return data.charAt(index);
	}
	@Override
	public boolean equals(Object o){
		if(this == o) return true;
		if(!(o instanceof AutoText text)) return false;
		return data.equals(text.data);
	}
	
	@Override
	public int hashCode(){
		return data.hashCode();
	}
	public String getData(){
		return data;
	}
}
