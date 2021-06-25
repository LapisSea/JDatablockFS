package com.lapissea.cfs.tools;

import java.awt.*;

class DrawUtils{
	static Rectangle makeBitRect(BinaryDrawing.RenderContext ctx, long trueOffset, int bitOffset, long siz){
		var bitCtx  =new BinaryDrawing.RenderContext(3, ctx.pixelsPerByte()/3);
		var range   =findBestContiguousRange(bitCtx, new BinaryDrawing.Range(bitOffset, bitOffset+siz));
		var byteRect=new BinaryDrawing.Range(trueOffset, trueOffset).toRect(ctx);
		var bitRect =range.toRect(bitCtx);
		
		bitRect.x+=byteRect.x;
		bitRect.y+=byteRect.y;
		return bitRect;
	}
	static BinaryDrawing.Range findBestContiguousRange(BinaryDrawing.RenderContext ctx, BinaryDrawing.Range range){
		var start       =(range.from()/ctx.width())*ctx.width();
		var nextLineFrom=start+ctx.width();
		if(nextLineFrom>=range.to()) return range;
		
		var siz      =range.size();
		var sizBefore=nextLineFrom-range.from();
		var sizAfter =Math.min(ctx.width(), siz-sizBefore);
		if(sizBefore>sizAfter) return new BinaryDrawing.Range(range.from(), nextLineFrom);
		return new BinaryDrawing.Range(nextLineFrom, nextLineFrom+sizAfter);
	}
	static String errorToMessage(Throwable e){
		StringBuilder message=new StringBuilder(e.getMessage()==null?"":e.getMessage());
		while(e.getCause()!=null){
			message.append("\nCause: ").append(e.getCause().getMessage());
			e=e.getCause();
		}
		return message.toString();
	}
}
