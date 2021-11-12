package com.lapissea.cfs.tools;

class DrawUtils{
	static BinaryGridRenderer.Rect makeBitRect(BinaryGridRenderer.RenderContext ctx, long trueOffset, int bitOffset, long siz){
		var bitCtx  =new BinaryGridRenderer.RenderContext(ctx.bytes(), ctx.filled(), 3, ctx.pixelsPerByte()/3);
		var range   =findBestContiguousRange(bitCtx, new BinaryGridRenderer.Range(bitOffset, bitOffset+siz));
		var byteRect=new BinaryGridRenderer.Range(trueOffset, trueOffset).toRect(ctx);
		var bitRect =range.toRect(bitCtx);
		
		bitRect.x+=byteRect.x;
		bitRect.y+=byteRect.y;
		return bitRect;
	}
	static BinaryGridRenderer.Range findBestContiguousRange(BinaryGridRenderer.RenderContext ctx, BinaryGridRenderer.Range range){
		var start       =(range.from()/ctx.width())*ctx.width();
		var nextLineFrom=start+ctx.width();
		if(nextLineFrom>=range.to()) return range;
		
		var siz      =range.size();
		var sizBefore=nextLineFrom-range.from();
		var sizAfter =Math.min(ctx.width(), siz-sizBefore);
		if(sizBefore>sizAfter) return new BinaryGridRenderer.Range(range.from(), nextLineFrom);
		return new BinaryGridRenderer.Range(nextLineFrom, nextLineFrom+sizAfter);
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
