package com.lapissea.fuzz.distributed.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

final class TarIO{
	private TarIO(){ }
	
	static void write(OutputStream out, List<RuntimeBundle.Artifact> artifacts) throws IOException{
		for(var artifact : artifacts){
			var header = new byte[512];
			var name   = artifact.name();
			var bytes  = name.getBytes(UTF_8);
			if(bytes.length>100){
				int slash = name.lastIndexOf('/');
				if(slash<0){ throw new IOException("Artifact path too long"); }
				put(header, 0, 100, name.substring(slash + 1));
				put(header, 345, 155, name.substring(0, slash));
			}else{
				put(header, 0, 100, name);
			}
			octal(header, 100, 8, 0644);
			octal(header, 108, 8, 0);
			octal(header, 116, 8, 0);
			
			long size = Files.size(artifact.file());
			octal(header, 124, 12, size);
			octal(header, 136, 12, 0);
			
			Arrays.fill(header, 148, 156, (byte)' ');
			header[156] = '0';
			put(header, 257, 6, "ustar");
			put(header, 263, 2, "00");
			int checksum = 0;
			for(byte b : header){
				checksum += b&255;
			}
			octal(header, 148, 8, checksum);
			out.write(header);
			Files.copy(artifact.file(), out);
			out.write(new byte[(int)((512 - size%512)%512)]);
		}
		out.write(new byte[1024]);
	}
	
	static List<String> extract(InputStream source, Path destination) throws IOException{
		var base   = destination.toAbsolutePath().normalize();
		var names  = new ArrayList<String>();
		var header = new byte[512];
		while(readFully(source, header)){
			if(isZeroBlock(header)){ break; }
			verifyChecksum(header);
			var name = parseName(header);
			var size = parseOctal(header, 124, 12);
			var type = (char)header[156];
			if(type != '0' && type != '\0'){
				throw new IOException("Unsupported tar entry type for: " + name);
			}
			var target = base.resolve(name).normalize();
			if(!target.startsWith(base)){
				throw new IOException("Unsafe tar entry name: " + name);
			}
			Files.createDirectories(target.getParent());
			if(Files.isSymbolicLink(target)){
				throw new IOException("Unsafe tar entry target (symbolic link): " + name);
			}
			try(var out = Files.newOutputStream(target, StandardOpenOption.CREATE,
			                                    StandardOpenOption.TRUNCATE_EXISTING,
			                                    LinkOption.NOFOLLOW_LINKS)){
				var remaining = size;
				var buffer    = new byte[1<<16];
				while(remaining>0){
					var read = source.read(buffer, 0, (int)Math.min(buffer.length, remaining));
					if(read<0){ throw new IOException("Truncated tar entry: " + name); }
					out.write(buffer, 0, read);
					remaining -= read;
				}
			}
			readFully(source, new byte[(int)((512 - size%512)%512)]);
			names.add(name);
		}
		return names;
	}
	
	private static void put(byte[] target, int offset, int length, String value) throws IOException{
		var bytes = value.getBytes(UTF_8);
		if(bytes.length>length){ throw new IOException("Artifact path too long"); }
		System.arraycopy(bytes, 0, target, offset, bytes.length);
	}
	private static void octal(byte[] target, int offset, int length, long value) throws IOException{
		var digits = Long.toOctalString(value);
		if(digits.length()>length - 1){ throw new IOException("Artifact too large"); }
		put(target, offset, length, "0".repeat(length - 1 - digits.length()) + digits);
	}
	
	private static boolean readFully(InputStream source, byte[] buffer) throws IOException{
		int offset = 0;
		while(offset<buffer.length){
			var read = source.read(buffer, offset, buffer.length - offset);
			if(read<0){
				if(offset != 0){ throw new IOException("Truncated tar stream"); }
				return false;
			}
			offset += read;
		}
		return true;
	}
	private static boolean isZeroBlock(byte[] block){
		for(byte b : block){
			if(b != 0){ return false; }
		}
		return true;
	}
	private static void verifyChecksum(byte[] header) throws IOException{
		long declared = parseOctal(header, 148, 8);
		long sum      = 0;
		for(int i = 0; i<header.length; i++){
			// The checksum field is treated as spaces when the checksum was computed
			sum += i>=148 && i<156? ' ' : header[i]&255;
		}
		if(sum != declared){ throw new IOException("Corrupt tar header"); }
	}
	private static String parseName(byte[] header) throws IOException{
		var name   = field(header, 0, 100);
		var prefix = field(header, 345, 155);
		if(name.isEmpty()){ throw new IOException("Tar entry without a name"); }
		return prefix.isEmpty()? name : prefix + "/" + name;
	}
	private static String field(byte[] header, int offset, int length){
		int end = offset;
		while(end<offset + length && header[end] != 0){ end++; }
		return new String(header, offset, end - offset, US_ASCII);
	}
	private static long parseOctal(byte[] header, int offset, int length) throws IOException{
		var digits = field(header, offset, length).replace(" ", "").replace("\0", "");
		if(digits.isEmpty()){ return 0; }
		try{
			return Long.parseLong(digits, 8);
		}catch(NumberFormatException e){
			throw new IOException("Corrupt tar numeric field at " + offset + ": " + digits, e);
		}
	}
}
