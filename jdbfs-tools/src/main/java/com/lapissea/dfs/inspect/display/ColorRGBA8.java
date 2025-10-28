package com.lapissea.dfs.inspect.display;

import com.lapissea.dfs.logging.Log;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.util.Objects;

public class ColorRGBA8 implements AutoCloseable{
	
	public static ColorRGBA8 fromJar(String path) throws IOException{
		ByteBuffer imageFile = null;
		try{
			imageFile = VUtils.nativeMemCopy(VUtils.readResource(path));
			return fromFileData(path, imageFile);
		}finally{
			if(imageFile != null) MemoryUtil.memFree(imageFile);
		}
	}
	public static ColorRGBA8 fromFile(File file) throws IOException{
		try(var io = new RandomAccessFile(file, "r")){
			var imageFile = io.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, io.length());
			return fromFileData(file.toString(), imageFile);
		}
	}
	public static ColorRGBA8 fromFileData(String name, ByteBuffer imageFile) throws IOException{
		ByteBuffer pixels = null;
		try(var stack = MemoryStack.stackPush();){
			IntBuffer xB = stack.mallocInt(1), yB = stack.mallocInt(1), channelsB = stack.mallocInt(1);
			pixels = STBImage.stbi_load_from_memory(imageFile, xB, yB, channelsB, STBImage.STBI_rgb_alpha);
			
			if(pixels == null) throw new IOException("Image \"" + name + "\" was invalid");
			
			int width  = xB.get(0);
			int height = yB.get(0);
			
			Log.info("loaded image " + name + " " + xB.get(0) + "x" + yB.get(0));
			
			var pixOwn = pixels;
			pixels = null;
			return new ColorRGBA8(pixOwn, width, height);
		}finally{
			if(pixels != null) MemoryUtil.memFree(pixels);
		}
	}
	
	private      ByteBuffer pixels;
	public final int        width, height;
	
	public ColorRGBA8(ByteBuffer pixels, int width, int height){
		this.pixels = Objects.requireNonNull(pixels);
		this.width = width;
		this.height = height;
		
	}
	
	public ByteBuffer getPixels(){
		return pixels;
	}
	@Override
	public void close(){
		MemoryUtil.memFree(pixels);
		pixels = null;
	}
}
