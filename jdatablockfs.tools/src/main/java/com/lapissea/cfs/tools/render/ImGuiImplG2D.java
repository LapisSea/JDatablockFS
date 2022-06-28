package com.lapissea.cfs.tools.render;

import com.lapissea.util.MathUtil;
import imgui.*;
import imgui.flag.ImGuiBackendFlags;
import imgui.type.ImInt;

import java.awt.Color;
import java.awt.CompositeContext;
import java.awt.Graphics2D;
import java.awt.TexturePaint;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class ImGuiImplG2D{
	
	private BufferedImage fontImage;
	private ByteBuffer    vertexBuffer=ByteBuffer.allocate(256);
	
	// Used to store tmp renderer data
	private final ImVec2 displaySize     =new ImVec2();
	private final ImVec2 framebufferScale=new ImVec2();
	private final ImVec2 displayPos      =new ImVec2();
	private final ImVec4 clipRect        =new ImVec4();
	
	public void init(){
		setupBackendCapabilitiesFlags();
		createDeviceObjects();
	}
	
	
	public void renderDrawData(Graphics2D g, final ImDrawData drawData){
		if(drawData.getCmdListsCount()<=0){
			return;
		}
		
		drawData.getDisplaySize(displaySize);
		drawData.getDisplayPos(displayPos);
		drawData.getFramebufferScale(framebufferScale);
		
		final float clipOffX  =displayPos.x;
		final float clipOffY  =displayPos.y;
		final float clipScaleX=framebufferScale.x;
		final float clipScaleY=framebufferScale.y;
		
		final int fbWidth =(int)(displaySize.x*framebufferScale.x);
		final int fbHeight=(int)(displaySize.y*framebufferScale.y);
		
		if(fbWidth<=0||fbHeight<=0){
			return;
		}
		
		// Render command lists
		for(int cmdListIdx=0;cmdListIdx<drawData.getCmdListsCount();cmdListIdx++){
			
			// Upload vertex/index buffers
			var vb=drawData.getCmdListVtxBufferData(cmdListIdx);
			if(vb.limit()>vertexBuffer.limit()){
				vertexBuffer=ByteBuffer.allocate(vb.limit()).order(ByteOrder.nativeOrder());
			}
			vertexBuffer.clear().put(vb).flip();
			vb=vertexBuffer;
			
			var ib=drawData.getCmdListIdxBufferData(cmdListIdx).asCharBuffer();
			
			Path2D.Float tri=new Path2D.Float();
			
			for(int cmdBufferIdx=0;cmdBufferIdx<drawData.getCmdListCmdBufferSize(cmdListIdx);cmdBufferIdx++){
				drawData.getCmdListCmdBufferClipRect(cmdListIdx, cmdBufferIdx, clipRect);
				final float clipMinX=(clipRect.x-clipOffX)*clipScaleX;
				final float clipMinY=(clipRect.y-clipOffY)*clipScaleY;
				final float clipMaxX=(clipRect.z-clipOffX)*clipScaleX;
				final float clipMaxY=(clipRect.w-clipOffY)*clipScaleY;
				
				if(clipMaxX<=clipMinX||clipMaxY<=clipMinY){
					continue;
				}
				
				g.setClip((int)clipMinX, (int)(clipMinY), (int)(clipMaxX-clipMinX), (int)(clipMaxY-clipMinY));
				
				// Bind texture, Draw
				final int textureId      =drawData.getCmdListCmdBufferTextureId(cmdListIdx, cmdBufferIdx);
				final int elemCount      =drawData.getCmdListCmdBufferElemCount(cmdListIdx, cmdBufferIdx);
				final int idxBufferOffset=drawData.getCmdListCmdBufferIdxOffset(cmdListIdx, cmdBufferIdx);
				final int vtxBufferOffset=drawData.getCmdListCmdBufferVtxOffset(cmdListIdx, cmdBufferIdx);
				
				ib.position(idxBufferOffset);
				for(int i=0;i<elemCount/3;i++){
					int i1=ib.get();
					int i2=ib.get();
					int i3=ib.get();
					
					vb.position(i1*ImDrawData.SIZEOF_IM_DRAW_VERT+vtxBufferOffset);
					float x1=vb.getFloat(), y1=vb.getFloat(), u1=vb.getFloat(), v1=vb.getFloat();
					
					Color color=new Color(vb.getInt(), true);
					
					vb.position(i2*ImDrawData.SIZEOF_IM_DRAW_VERT+vtxBufferOffset);
					float x2=vb.getFloat(), y2=vb.getFloat(), u2=vb.getFloat(), v2=vb.getFloat();
					vb.position(i3*ImDrawData.SIZEOF_IM_DRAW_VERT+vtxBufferOffset);
					float x3=vb.getFloat(), y3=vb.getFloat(), u3=vb.getFloat(), v3=vb.getFloat();
					
					if(textureId==69){
						
						float ul=MathUtil.min(u1, u2, u3);
						float ur=MathUtil.max(u1, u2, u3);
						float vl=MathUtil.min(v1, v2, v3);
						float vr=MathUtil.max(v1, v2, v3);
						float us=ur-ul, vs=vr-vl;
						
						if(Math.min(us, vs)<0.00001){
							g.setPaintMode();
							g.setColor(color);
						}else{
							float xl=MathUtil.min(x1, x2, x3);
							float xr=MathUtil.max(x1, x2, x3);
							float yl=MathUtil.min(y1, y2, y3);
							float yr=MathUtil.max(y1, y2, y3);
							float w =xr-xl, h=yr-yl;
							
							float iw=fontImage.getHeight();
							float ih=fontImage.getHeight();
							
							iw*=w/(us*iw);
							ih*=h/(vs*ih);
							
							var paint=new TexturePaint(fontImage, new Rectangle2D.Float(
								xl-ul*iw, yl-vl*ih,
								iw, ih)
							);
							
							
							g.setComposite((srcCM, dstCM, hints)->new CompositeContext(){
								@Override
								public void dispose(){}
								
								public void compose(Raster src1, Raster src2, WritableRaster dst){
									var rect=dst.getBounds();
									
									int[] p1=null, p2=null;
									for(int x=rect.x;x<rect.x+rect.width;x++){
										for(int y=rect.y;y<rect.y+rect.height;y++){
											p1=src1.getPixel(x, y, p1);
											p2=src2.getPixel(x, y, p2);
											
											p1[0]*=color.getRed()/255F;
											p1[1]*=color.getGreen()/255F;
											p1[2]*=color.getBlue()/255F;
											p1[3]*=color.getAlpha()/255F;
											
											var a=p1[3]/255F;
											for(int j=0;j<3;j++){
												p1[j]=(int)(p1[j]*a+p2[j]*(1-a));
											}
											
											dst.setPixel(x, y, p1);
										}
									}
								}
							});
							g.setPaint(paint);
						}
					}else{
						g.setPaintMode();
						g.setColor(color);
					}
					
					tri.reset();
					tri.moveTo(x1, y1);
					tri.lineTo(x2, y2);
					tri.lineTo(x3, y3);
					tri.closePath();
					
					g.fill(tri);
					
				}
			}
		}
		
		g.setClip(null);
		g.setColor(Color.WHITE);
		g.setPaintMode();
		g.setPaint(Color.WHITE);
	}
	
	/**
	 * Method rebuilds the font atlas for Dear ImGui. Could be used to update application fonts in runtime.
	 */
	public void updateFontsTexture(){
		
		final ImFontAtlas fontAtlas=ImGui.getIO().getFonts();
		final ImInt       width    =new ImInt();
		final ImInt       height   =new ImInt();
		final ByteBuffer  buffer   =fontAtlas.getTexDataAsAlpha8(width, height);
		
		fontImage=new BufferedImage(width.get(), height.get(), BufferedImage.TYPE_INT_ARGB);
		for(int y=0;y<height.get();y++){
			for(int x=0;x<width.get();x++){
				var r=0xFF;
				var g=0xFF;
				var b=0xFF;
				var a=buffer.get()&0xFF;
				fontImage.setRGB(x, y, (a<<24)|(r<<16)|(g<<8)|(b<<0));
			}
		}
		
		fontAtlas.setTexID(69);
	}
	
	private void setupBackendCapabilitiesFlags(){
		ImGuiIO io=ImGui.getIO();
		
		io.setBackendRendererName("imgui_java_impl_g2d");
		io.addBackendFlags(ImGuiBackendFlags.RendererHasVtxOffset);
	}
	
	private void createDeviceObjects(){
		updateFontsTexture();
	}
	
	public void dispose(){
		ImGui.destroyPlatformWindows();
	}
	
}
