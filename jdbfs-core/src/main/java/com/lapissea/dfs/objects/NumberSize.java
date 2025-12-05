package com.lapissea.dfs.objects;

import com.lapissea.dfs.exceptions.OutOfBitDepth;
import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.io.bit.EnumUniverse;
import com.lapissea.dfs.io.content.BBView;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.utils.OptionalPP;
import com.lapissea.dfs.utils.iterableplus.IterableIntPP;
import com.lapissea.dfs.utils.iterableplus.IterableLongPP;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.exceptions.MalformedJorth;

import java.io.IOException;
import java.math.BigInteger;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static java.util.Comparator.comparingInt;

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
	
	private static final NumberSize[] BYTE_MAP;
	
	static{
		var len  = 0;
		var vals = values();
		for(var n : vals) len = Math.max(len, n.bytes);
		var res = new NumberSize[len + 1];
		for(var n : vals){
			for(int i = 0; i<n.bytes + 1; i++){
				res[i] = res[i] == null? n : n.min(res[i]);
			}
		}
		BYTE_MAP = res;
	}
	
	public static final  EnumUniverse<NumberSize> FLAG_INFO = EnumUniverse.of(NumberSize.class);
	private static final NumberSize[]             VALUES    = values();
	
	public static final NumberSize LARGEST = LONG;
	
	static{
		for(NumberSize ns : VALUES){
			ns.prev = FLAG_INFO.filter(n -> n.lesserThan(ns)).max(comparingInt(NumberSize::bytes));
			ns.next = FLAG_INFO.filter(n -> n.greaterThan(ns)).min(comparingInt(NumberSize::bytes)).orElse(null);
		}
	}
	
	public static NumberSize ordinal(int index){
		return VALUES[index];
	}
	
	public static NumberSize bySize(ChunkPointer size){
		return bySize(size.getValue());
	}
	
	public static NumberSize bySize(long size, boolean unsigned){
		return unsigned? bySize(Math.max(0, size)) : bySizeSigned(size);
	}
	
	public static NumberSize bySize(int size, boolean unsigned){
		return unsigned? bySize(Math.max(0, size)) : bySizeSigned(size);
	}
	
	public static NumberSize bySizeSigned(IterableIntPP.Bounds size){
		var sizeA = bySizeSigned(size.min());
		var sizeB = bySizeSigned(size.max());
		return sizeA.max(sizeB);
	}
	public static NumberSize bySizeSigned(IterableLongPP.Bounds size){
		var sizeA = bySizeSigned(size.min());
		var sizeB = bySizeSigned(size.max());
		return sizeA.max(sizeB);
	}
	public static NumberSize bySizeSigned(long size){
		if(size == 0) return VOID;
		var off = size<0? -(size + 1) : size;
		return byBytes(BitUtils.bitsToBytes(Long.SIZE - Long.numberOfLeadingZeros(off) + 1));
	}
	public static NumberSize bySizeSigned(int size){
		if(size == 0) return VOID;
		var off = size<0? -(size + 1) : size;
		return byBytes(BitUtils.bitsToBytes(Integer.SIZE - Integer.numberOfLeadingZeros(off) + 1));
	}
	
	public static NumberSize bySize(int size){
		if(size<0){
			throw new IllegalArgumentException(size + "");
		}
		return byBytes(BitUtils.bitsToBytes(Integer.SIZE - Integer.numberOfLeadingZeros(size)));
	}
	public static NumberSize bySize(long size){
		if(size<0){
			throw new IllegalArgumentException(size + "");
		}
		return byBytes(BitUtils.bitsToBytes(Long.SIZE - Long.numberOfLeadingZeros(size)));
	}
	
	public static NumberSize byBits(int bits){
		return byBytes(BitUtils.bitsToBytes(bits));
	}
	
	public static NumberSize byBytes(int bytes){
		return BYTE_MAP[bytes];
	}
	
	private NumberSize             next;
	private OptionalPP<NumberSize> prev;
	
	public final int  bytes;
	public final long maxSize;
	public final int  maxSizeI;
	public final long signedMaxValue;
	public final int  signedMaxValueI;
	public final long signedMinValue;
	public final char shortName;
	
	public final OptionalInt  optionalBytes;
	public final OptionalLong optionalBytesLong;
	
	NumberSize(char shortName, int bytes){
		this.shortName = shortName;
		this.bytes = bytes;
		this.maxSize = BigInteger.ONE.shiftLeft(bytes*8).subtract(BigInteger.ONE).min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
		var signBase = BigInteger.ONE.shiftLeft(bytes*8 - 1);
		this.signedMaxValue = signBase.subtract(BigInteger.ONE).max(BigInteger.ZERO).longValueExact();
		this.signedMinValue = signBase.multiply(BigInteger.valueOf(-1)).longValueExact();
		
		optionalBytes = OptionalInt.of(bytes);
		optionalBytesLong = OptionalLong.of(bytes);
		
		maxSizeI = (int)Math.min(maxSize, Integer.MAX_VALUE);
		signedMaxValueI = (int)Math.min(signedMaxValue, Integer.MAX_VALUE);
	}
	
	public float readFloat(ContentReader in) throws IOException{
		return switch(this){
			case VOID -> 0;
			case SHORT -> in.readFloat2();
			case INT -> in.readFloat4();
			case BYTE, SMALL_INT, BIG_INT, SMALL_LONG, LONG -> throw new UnsupportedOperationException();
		};
	}
	
	public void writeFloat(ContentWriter out, float value) throws IOException{
		switch(this){
			case VOID -> { }
			case SHORT -> out.writeFloat2(value);
			case INT -> out.writeFloat4(value);
			case BYTE, SMALL_INT, BIG_INT, SMALL_LONG, LONG -> throw new UnsupportedOperationException();
		}
	}
	
	public double readDouble(ContentReader in) throws IOException{
		return switch(this){
			case VOID -> 0;
			case SHORT -> in.readFloat2();
			case INT -> in.readFloat4();
			case LONG -> in.readFloat8();
			case BYTE, SMALL_INT, BIG_INT, SMALL_LONG -> throw new UnsupportedOperationException();
		};
	}
	
	public void writeDouble(ContentWriter out, double value) throws IOException{
		switch(this){
			case VOID -> { }
			case SHORT -> out.writeFloat2((float)value);
			case INT -> out.writeFloat4((float)value);
			case LONG -> out.writeFloat8(value);
			case BYTE, SMALL_INT, BIG_INT, SMALL_LONG -> throw new UnsupportedOperationException();
		}
	}
	
	
	/**
	 * Stack: pops ContentReader, pushes float
	 */
	public void readFloatConst(CodeStream target, String getContentReader) throws MalformedJorth{
		switch(this){
			case VOID -> target.write("0");
			case SHORT -> {
				target.write("{} call readFloat2", getContentReader);
			}
			case INT -> {
				target.write("{} call readFloat4", getContentReader);
			}
			case BIG_INT, SMALL_LONG, LONG -> throw new MalformedJorth("Attempted to read too large of a number");
		}
	}
	/**
	 * Stack: pops NumberSize, pushes int
	 */
	public static void readFloatDyn(CodeStream target, String getContentReader) throws MalformedJorth{
		target.write(
			"""
				call readFloat start
					{}
				end
				""",
			getContentReader
		);
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
	
	/**
	 * Stack: pops ContentReader, pushes long or int
	 */
	public void readConst(CodeStream target, String getContentReader, boolean signed) throws MalformedJorth{
		switch(this){
			case VOID -> target.write("0");
			case BYTE, SHORT, SMALL_INT, INT, BIG_INT, SMALL_LONG -> {
				target.write("{} call {}", getContentReader, (signed? "readInt" : "readUnsignedInt") + bytes);
			}
			case LONG -> target.write("{} call readInt8", getContentReader);
		}
	}
	
	public int readInt(ContentReader in) throws IOException{
		return switch(this){
			case VOID -> 0;
			case BYTE -> in.readUnsignedInt1();
			case SHORT -> in.readUnsignedInt2();
			case SMALL_INT -> in.readUnsignedInt3();
			case INT -> (int)in.readUnsignedInt4();
			case BIG_INT, SMALL_LONG, LONG -> throw new IOException("Attempted to read too large of a number");
		};
	}
	
	/**
	 * Stack: pops ContentReader, pushes int
	 */
	public void readIntConst(CodeStream target, String getContentReader, boolean signed) throws MalformedJorth{
		switch(this){
			case VOID -> target.write("0");
			case BYTE, SHORT, SMALL_INT -> {
				target.write("{} call {}", getContentReader, (signed? "readInt" : "readUnsignedInt") + bytes);
			}
			case INT -> {
				if(signed) target.write("{} call readInt4", getContentReader);
				else target.write("{} call readUnsignedInt4 cast int", getContentReader);
			}
			case BIG_INT, SMALL_LONG, LONG -> throw new MalformedJorth("Attempted to read too large of a number");
		}
	}
	
	/**
	 * Stack: pops NumberSize, pushes int
	 */
	public static void readIntDyn(CodeStream target, String getContentReader, boolean signed) throws MalformedJorth{
		target.write(
			"""
				call {} start
					{}
				end
				""",
			signed? "readIntSigned" : "readInt",
			getContentReader
		);
	}
	/**
	 * Stack: pops NumberSize, pushes long
	 */
	public static void readDyn(CodeStream target, String getContentReader, boolean signed) throws MalformedJorth{
		target.write(
			"""
				call {} start
					{}
				end
				""",
			signed? "readSigned" : "read",
			getContentReader
		);
	}
	
	public void skip(ContentReader in) throws IOException{
		in.skipExact(bytes);
	}
	
	public long readSigned(ContentReader in) throws IOException{
		if(this == VOID) return 0;
		return toSigned(read(in));
	}
	
	public int readIntSigned(ContentReader in) throws IOException{
		if(this == VOID) return 0;
		return toSigned(readInt(in));
	}
	
	public void write(ContentWriter out, ChunkPointer value) throws IOException{
		write(out, value == null? 0 : value.getValue());
	}
	
	public void write(ContentWriter out, long value) throws IOException{
		if(DEBUG_VALIDATION) validateUnsigned(value);
		
		switch(this){
			case VOID -> { }
			case BYTE -> out.writeInt1((int)value);
			case SHORT -> out.writeInt2((int)value);
			case SMALL_INT -> out.writeInt3((int)value);
			case INT -> out.writeInt4((int)value);
			case BIG_INT -> out.writeInt5(value);
			case SMALL_LONG -> out.writeInt6(value);
			case LONG -> out.writeInt8(value);
		}
	}
	
	public void write(byte[] out, int off, ChunkPointer value) throws IOException{
		write(out, off, value == null? 0 : value.getValue());
	}
	
	public void write(byte[] out, int off, long value) throws IOException{
		if(DEBUG_VALIDATION) validateUnsigned(value);
		
		switch(this){
			case VOID -> { }
			case BYTE -> BBView.writeInt1(out, off, (byte)value);
			case SHORT -> BBView.writeInt2(out, off, (short)value);
			case SMALL_INT -> BBView.writeInt3(out, off, (int)value);
			case INT -> BBView.writeInt4(out, off, (int)value);
			case BIG_INT -> BBView.writeInt5(out, off, value);
			case SMALL_LONG -> BBView.writeInt6(out, off, value);
			case LONG -> BBView.writeInt8(out, off, value);
		}
	}
	
	public void writeInt(ContentWriter out, int value, boolean unsigned) throws IOException{
		if(unsigned){
			writeInt(out, value);
		}else{
			writeIntSigned(out, value);
		}
	}
	
	public void writeInt(ContentWriter out, int value) throws IOException{
		if(DEBUG_VALIDATION) validateUnsigned(value);
		
		switch(this){
			case VOID -> { }
			case BYTE -> out.writeInt1(value);
			case SHORT -> out.writeInt2(value);
			case SMALL_INT -> out.writeInt3(value);
			case INT -> out.writeInt4(value);
			case BIG_INT, SMALL_LONG, LONG -> throw new IOException("Attempted to write int in to too wide of a value");
		}
	}
	
	private void validateUnsigned(long value){
		try{
			ensureCanFit(value);
		}catch(OutOfBitDepth e){
			throw new AssertionError(e);
		}
		if(value<0 && this != LONG && this != VOID){
			throw new IllegalStateException(value + " is signed! " + this);
		}
	}
	private void validateUnsigned(int value){
		try{
			ensureCanFit(value);
		}catch(OutOfBitDepth e){
			throw new AssertionError(e);
		}
		if(value<0 && this != INT && this != VOID){
			throw new IllegalStateException(value + " is signed! " + this);
		}
	}
	
	private long toUnsigned(long value){ return this == LONG || value>=0? value : value + maxSize + 1; }
	private int toUnsigned(int value)  { return this == INT || value>=0? value : value + maxSizeI + 1; }
	private long toSigned(long value)  { return this == LONG || value<=signedMaxValue? value : value - maxSize - 1; }
	private int toSigned(int value)    { return this == INT || value<=signedMaxValueI? value : value - maxSizeI - 1; }
	
	private void validateSigned(long value){
		if(this != VOID){
			var un = toUnsigned(value);
			if(this != LONG) validateUnsigned(un);
			if(this != LONG && un<0) throw new AssertionError(un + "<0");
			var back = toSigned(un);
			if(value != back) throw new AssertionError(value + "!=" + back);
		}
		
		try{
			ensureCanFitSigned(value);
		}catch(OutOfBitDepth e){
			throw new RuntimeException(e);
		}
	}
	private void validateSigned(int value){
		try{
			ensureCanFitSigned(value);
		}catch(OutOfBitDepth e){
			throw new RuntimeException(e);
		}
		
		if(this != VOID){
			var un = toUnsigned(value);
			if(this != INT) validateUnsigned(un);
			if(this != INT && un<0) throw new AssertionError(un + "<0");
			var back = toSigned(un);
			if(value != back) throw new AssertionError(value + "!=" + back);
		}
	}
	
	public void writeSigned(ContentWriter out, long value) throws IOException{
		if(DEBUG_VALIDATION) validateSigned(value);
		if(this == VOID) return;
		write(out, toUnsigned(value));
	}
	
	public void writeIntSigned(ContentWriter out, int value) throws IOException{
		if(DEBUG_VALIDATION) validateSigned(value);
		if(this == VOID) return;
		writeInt(out, toUnsigned(value));
	}
	
	public OptionalPP<NumberSize> prev(){ return prev; }
	public NumberSize next()            { return next; }
	
	/// ////////
	
	public boolean greaterThanOrEqual(NumberSize other){ return other == this || bytes>=other.bytes; }
	public boolean lesserThanOrEqual(NumberSize other){ return other == this || bytes<=other.bytes; }
	public boolean greaterThan(NumberSize other)      { return other != this && bytes>other.bytes; }
	public boolean lesserThan(NumberSize other)       { return other != this && bytes<other.bytes; }
	
	/// ////////
	
	public NumberSize max(NumberSize other){ return greaterThan(other)? this : other; }
	public NumberSize min(NumberSize other){ return lesserThan(other)? this : other; }
	
	/// ////////
	
	public boolean canFit(ChunkPointer num){ return canFit(num.getValue()); }
	public boolean canFit(long num)                { return num<=maxSize; }
	public boolean canFit(int num)                 { return num<=maxSize; }
	public boolean canFitSigned(long num)          { return signedMinValue<=num && num<=signedMaxValue; }
	public boolean canFitSigned(int num)           { return signedMinValue<=num && num<=signedMaxValueI; }
	public boolean canFit(long num, boolean signed){ return signed? canFitSigned(num) : canFit(num); }
	public boolean canFit(int num, boolean signed) { return signed? canFitSigned(num) : canFit(num); }
	
	public long remaining(long num){
		return maxSize - num;
	}
	
	private void depthFail(long num, boolean signed) throws OutOfBitDepth{
		throw new OutOfBitDepth(this, num, signed);
	}
	
	public void ensureCanFit(ChunkPointer num) throws OutOfBitDepth{ ensureCanFit(num.getValue()); }
	public void ensureCanFit(long num) throws OutOfBitDepth        { if(!canFit(num)) depthFail(num, false); }
	public void ensureCanFit(int num) throws OutOfBitDepth         { if(!canFit(num)) depthFail(num, false); }
	public void ensureCanFitSigned(long num) throws OutOfBitDepth  { if(!canFitSigned(num)) depthFail(num, true); }
	public void ensureCanFitSigned(int num) throws OutOfBitDepth   { if(!canFitSigned(num)) depthFail(num, true); }
	
	public String binaryString(long value){
		var    bitCount = bits();
		String bits     = Long.toBinaryString(value&BitUtils.makeMask(bitCount));
		if(bits.length() == bitCount) return bits;
		return "0".repeat(bitCount - bits.length()) + bits;
	}
	
	public int bytes()                     { return bytes; }
	public int bits()                      { return bytes*Byte.SIZE; }
	public long maxSize()                  { return maxSize; }
	public long signedMinValue()           { return signedMinValue; }
	public long signedMaxValue()           { return signedMaxValue; }
	public char shortName()                { return shortName; }
	public OptionalInt optionalBytes()     { return optionalBytes; }
	public OptionalLong optionalBytesLong(){ return optionalBytesLong; }
}
