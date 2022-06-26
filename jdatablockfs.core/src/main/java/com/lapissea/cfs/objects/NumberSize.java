package com.lapissea.cfs.objects;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.cfs.io.bit.BitUtils;
import com.lapissea.cfs.io.bit.EnumUniverse;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.ShouldNeverHappenError;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Comparator;
import java.util.OptionalInt;
import java.util.OptionalLong;

@SuppressWarnings("unused")
public enum NumberSize{
	
	// @formatter:off
	VOID      ('V', 0),
	BYTE      ('B', 1),
	SHORT     ('s', 2),
	SMALL_INT ('S', 3),
	INT       ('i', 4),
	BIG_INT   ('I', 5),
	SMALL_LONG('l', 6),
	LONG      ('L', 8);
	// @formatter:on
	
	private static final long[] MAX_SIZES=Arrays.stream(values()).mapToLong(NumberSize::maxSize).toArray();
	private static final int[]  BYTES    =Arrays.stream(values()).mapToInt(NumberSize::bytes).toArray();
	
	public static final EnumUniverse<NumberSize> FLAG_INFO=EnumUniverse.get(NumberSize.class);
	
	public static final NumberSize SMALEST_REAL=BYTE;
	public static final NumberSize LARGEST     =LONG;
	
	public static NumberSize ordinal(int index){
		return FLAG_INFO.get(index);
	}
	
	public static NumberSize bySizeVoidable(@Nullable INumber size){
		return size==null?VOID:bySize(size);
	}
	
	public static NumberSize bySize(INumber size){
		return bySize(size.getValue());
	}
	
	public static NumberSize bySize(long size){
		for(int i=0;i<MAX_SIZES.length;i++){
			if(MAX_SIZES[i]>=size) return FLAG_INFO.get(i);
		}
		throw new RuntimeException("Extremely large value: "+size);
	}
	
	public static NumberSize byBits(int bits){
		return byBytes(Utils.bitToByte(bits));
	}
	
	public static NumberSize byBytes(int bytes){
		for(int i=0;i<BYTES.length;i++){
			if(BYTES[i]>=bytes){
				if(FLAG_INFO==null) return values()[i];
				return FLAG_INFO.get(i);
			}
		}
		throw new RuntimeException("Extremely large byte length: "+bytes);
	}
	
	private int nextId=-2;
	private int prevId=-2;
	
	public final int  bytes;
	public final long maxSize;
	public final char shortName;
	
	public final OptionalInt  optionalBytes;
	public final OptionalLong optionalBytesLong;
	
	NumberSize(char shortName, int bytes){
		this.shortName=shortName;
		this.bytes=bytes;
		this.maxSize=BigInteger.ONE.shiftLeft(bytes*8).subtract(BigInteger.ONE).min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
		
		optionalBytes=OptionalInt.of(bytes);
		optionalBytesLong=OptionalLong.of(bytes);
	}
	
	public double readFloating(ContentReader in) throws IOException{
		return switch(this){
			case INT -> Float.intBitsToFloat(in.readInt4());
			case LONG -> Double.longBitsToDouble(in.readInt8());
			case SHORT -> Utils.shortBitsToFloat(in.readInt2());
			case VOID -> 0;
			case BYTE, SMALL_INT, BIG_INT, SMALL_LONG -> throw new UnsupportedOperationException();
		};
	}
	
	public void writeFloating(ContentWriter out, double value) throws IOException{
		switch(this){
			case INT -> out.writeInt4(Float.floatToIntBits((float)value));
			case LONG -> out.writeInt8(Double.doubleToLongBits(value));
			case SHORT -> out.writeInt2(Utils.floatToShortBits((float)value));
			case VOID -> {}
			case BYTE, SMALL_INT, BIG_INT, SMALL_LONG -> throw new UnsupportedOperationException();
			case null -> throw new ShouldNeverHappenError();
		}
	}
	
	public long read(ContentReader in) throws IOException{
		return switch(this){
			case VOID -> 0;
			case BYTE -> in.readUnsignedInt1();
			case SHORT -> in.readUnsignedInt2();
			case SMALL_INT -> in.readUnsignedInt3();
			case INT -> in.readUnsignedInt4();
			case BIG_INT -> in.readUnsignedInt5();
			case SMALL_LONG -> in.readUnsignedInt6();
			case LONG -> in.readInt8();
		};
	}
	
	public void write(ContentWriter out, INumber value) throws IOException{
		write(out, value==null?0:value.getValue());
	}
	
	public void write(ContentWriter out, long value) throws IOException{
		switch(this){
			case VOID -> {}
			case BYTE -> out.writeInt1((int)value);
			case SHORT -> out.writeInt2((int)value);
			case SMALL_INT -> out.writeInt3((int)value);
			case INT -> out.writeInt4((int)value);
			case BIG_INT -> out.writeInt5(value);
			case SMALL_LONG -> out.writeInt6(value);
			case LONG -> out.writeInt8(value);
			case null -> throw new ShouldNeverHappenError();
		}
	}
	
	public NumberSize prev(){
		if(prevId==-2){
			prevId=FLAG_INFO.stream().filter(n->n.lesserThan(this)).max(Comparator.comparingInt(NumberSize::bytes)).map(Enum::ordinal).orElse(-1);
		}
		if(prevId==-1) return this;
		return ordinal(prevId);
	}
	
	public NumberSize next(){
		if(nextId==-2){
			nextId=FLAG_INFO.stream().filter(n->n.greaterThan(this)).min(Comparator.comparingInt(NumberSize::bytes)).map(Enum::ordinal).orElse(-1);
		}
		if(nextId==-1) return this;
		return ordinal(nextId);
	}
	
	public boolean greaterThanOrEqual(NumberSize other){
		if(other==this) return false;
		return bytes>=other.bytes;
	}
	public boolean lesserThanOrEqual(NumberSize other){
		if(other==this) return false;
		return bytes<=other.bytes;
	}
	
	public boolean greaterThan(NumberSize other){
		if(other==this) return false;
		return bytes>other.bytes;
	}
	
	public boolean lesserThan(NumberSize other){
		if(other==this) return false;
		return bytes<other.bytes;
	}
	
	public NumberSize max(NumberSize other){
		return greaterThan(other)?this:other;
	}
	
	public NumberSize min(NumberSize other){
		return lesserThan(other)?this:other;
	}
	
	public boolean canFit(INumber num){
		return canFit(num.getValue());
	}
	
	public boolean canFit(long num){
		return num<=maxSize;
	}
	
	public long remaining(long num){
		return maxSize-num;
	}
	
	public void ensureCanFit(@Nullable ChunkPointer num) throws BitDepthOutOfSpaceException{
		ensureCanFit(ChunkPointer.getValueNullable(num));
	}
	
	public void ensureCanFit(@NotNull INumber num) throws BitDepthOutOfSpaceException{
		ensureCanFit(num.getValue());
	}
	
	public void ensureCanFit(long num) throws BitDepthOutOfSpaceException{
		if(!canFit(num)) throw new BitDepthOutOfSpaceException(this, num);
	}
	
	public void write(ContentWriter stream, long[] data) throws IOException{
		for(var l : data){
			write(stream, l);
		}
	}
	
	public NumberSize requireNonVoid(){
		if(this==VOID) throw new RuntimeException("Value must not be "+VOID);
		return this;
	}
	
	public int bytes(){
		return bytes;
	}
	
	public int bits(){
		return bytes*Byte.SIZE;
	}
	
	public String binaryString(long value){
		var    bitCount=bits();
		String bits    =Long.toBinaryString(value&BitUtils.makeMask(bitCount));
		if(bits.length()==bitCount) return bits;
		return "0".repeat(bitCount-bits.length())+bits;
	}
	
	public long maxSize(){
		return maxSize;
	}
	public char shortName(){
		return shortName;
	}
	public OptionalInt optionalBytes(){
		return optionalBytes;
	}
	public OptionalLong optionalBytesLong(){
		return optionalBytesLong;
	}
}
