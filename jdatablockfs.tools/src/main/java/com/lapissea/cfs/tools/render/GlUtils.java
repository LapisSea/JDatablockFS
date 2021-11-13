package com.lapissea.cfs.tools.render;

import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL32.GL_GEOMETRY_SHADER;

public class GlUtils{
	public static class Texture{
		private int id;
		private Texture(int id){
			this.id=id;
		}
		public void delete(){
			GL11.glDeleteTextures(id);
			id=-1;
		}
		
		/**
		 * @param target <ul>
		 *               <li>{@link GL11#GL_TEXTURE_2D}</li>
		 *               </ul>
		 */
		public void bind(int target){
			GL11.glBindTexture(target, id);
		}
	}
	
	private static Boolean ANOSOTROPIC_SUPPORTED;
	protected static boolean getAnosotropicSupported(){
		if(ANOSOTROPIC_SUPPORTED==null){
			String  str =GL11.glGetString(GL11.GL_EXTENSIONS);
			boolean supp=str!=null&&str.contains("GL_EXT_texture_filter_anisotropic");
			ANOSOTROPIC_SUPPORTED=supp;
			return supp;
		}
		return ANOSOTROPIC_SUPPORTED;
	}
	public static int makeShaderProgram(int... shaders){
		var program=glCreateProgram();
		for(int shader : shaders){
			glAttachShader(program, shader);
		}
		glLinkProgram(program);
		int    comp=glGetProgrami(program, GL_LINK_STATUS);
		int    len =glGetProgrami(program, GL_INFO_LOG_LENGTH);
		String err =glGetProgramInfoLog(program, len);
		String log ="";
		if(err.length()!=0) log=err+"\n"+log;
		log=log.trim();
		if(comp==GL11.GL_FALSE){
			throw new RuntimeException(log.length()!=0?log:"Could not link program");
		}
		return program;
	}
	public static int compileShader(int type, String source){
		int shader=glCreateShader(type);
		if(shader==0)
			throw new RuntimeException(
				"could not create shader object; check ShaderProgram.isSupported()");
		glShaderSource(shader, source);
		glCompileShader(shader);
		
		int    comp=glGetShaderi(shader, GL_COMPILE_STATUS);
		int    len =glGetShaderi(shader, GL_INFO_LOG_LENGTH);
		String t   =shaderTypeString(type);
		String err =glGetShaderInfoLog(shader, len);
		String log ="";
		if(err.length()!=0)
			log+=t+" compile log:\n"+err+"\n";
		if(comp==GL11.GL_FALSE)
			throw new RuntimeException(log.length()!=0?log:"Could not compile "+shaderTypeString(type));
		return shader;
	}
	private static String shaderTypeString(int type){
		return switch(type){
			case GL_FRAGMENT_SHADER -> "GL_FRAGMENT_SHADER";
			case GL_VERTEX_SHADER -> "GL_VERTEX_SHADER";
			case GL_GEOMETRY_SHADER -> "GL_GEOMETRY_SHADER";
			default -> "shader "+type;
		};
	}
	public static Texture uploadTexture(int width, int height, int format, ByteBuffer data){
		var id=GL11.glGenTextures();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, format, width, height, 0, format, GL11.GL_UNSIGNED_BYTE, data);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		
		if(getAnosotropicSupported()){
			float[] aniso={0};
			GL11.glGetFloatv(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, aniso);
			GL11.glTexParameterf(GL11.GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, aniso[0]);
		}
		return new Texture(id);
	}
}
