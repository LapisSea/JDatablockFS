package com.lapissea.fsf.collections.fixedlist.headers;

import com.lapissea.fsf.NumberSize;
import com.lapissea.fsf.chunk.ChunkPointer;
import com.lapissea.fsf.chunk.MutableChunkPointer;
import com.lapissea.fsf.collections.fixedlist.FixedLenList;
import com.lapissea.fsf.io.ContentInputStream;
import com.lapissea.fsf.io.ContentOutputStream;
import com.lapissea.fsf.io.serialization.FileObject;
import com.lapissea.util.UnsafeLongSupplier;

import java.io.IOException;
import java.util.List;

import static com.lapissea.fsf.NumberSize.*;

public class SizedNumber extends FileObject.FullLayout<SizedNumber> implements FixedLenList.ElementHead<SizedNumber, ChunkPointer>{
	
	private static final SequenceLayout<SizedNumber> LAYOUT=FileObject.sequenceBuilder(List.of(
		new FlagDef<>(BYTE,
		              (writer, head)->writer.writeEnum(head.size),
		              (reader, head)->head.size=reader.readEnum(NumberSize.class))
	                                                                                          ));
	
	private NumberSize size;
	
	private final UnsafeLongSupplier<IOException> extraSource;
	
	public SizedNumber(UnsafeLongSupplier<IOException> extraSource){
		this(null, extraSource);
	}
	
	public SizedNumber(NumberSize size, UnsafeLongSupplier<IOException> extraSource){
		super(LAYOUT);
		this.size=size;
		this.extraSource=extraSource;
	}
	
	@Override
	public SizedNumber copy(){
		return new SizedNumber(size, extraSource);
	}
	
	@Override
	public boolean willChange(ChunkPointer element) throws IOException{
		var val=element.getValue();
		return NumberSize.bySize(Math.max(val, extraSource.getAsLong())).max(size)!=size;
	}
	
	@Override
	public void update(ChunkPointer element) throws IOException{
		size=NumberSize.bySize(Math.max(element.getValue(), extraSource.getAsLong())).max(size);
	}
	
	@Override
	public int getElementSize(){
		return size.bytes;
	}
	
	@Override
	public ChunkPointer newElement(){
		return new MutableChunkPointer();
	}
	
	@Override
	public void readElement(ContentInputStream src, ChunkPointer dest) throws IOException{
		((MutableChunkPointer)dest).setValue(size.read(src));
	}
	
	@Override
	public void writeElement(ContentOutputStream dest, ChunkPointer src) throws IOException{
		size.write(dest, src.getValue());
	}
	
	@Override
	public String toString(){
		return size+"S";
	}
}
