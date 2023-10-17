package com.lapissea.dfs.tools;

import com.google.gson.GsonBuilder;
import com.lapissea.util.function.UnsafeSupplier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

@SuppressWarnings("unused")
public class MSDFAtlas{
	public static final class Info{
		private final String type;
		private final int    distanceRange;
		private final double size;
		private final int    width;
		private final int    height;
		private final String yOrigin;
		public Info(String type, int distanceRange, int size, int width, int height, String yOrigin){
			this.type = type;
			this.distanceRange = distanceRange;
			this.size = size;
			this.width = width;
			this.height = height;
			this.yOrigin = yOrigin;
		}
		public String getType(){
			return type;
		}
		public int getDistanceRange(){
			return distanceRange;
		}
		public double getSize(){
			return size;
		}
		public int getWidth(){
			return width;
		}
		public int getHeight(){
			return height;
		}
		public String getyOrigin(){
			return yOrigin;
		}
		@Override
		public boolean equals(Object obj){
			if(obj == this) return true;
			if(obj == null || obj.getClass() != this.getClass()) return false;
			var that = (Info)obj;
			return Objects.equals(this.type, that.type) &&
			       this.distanceRange == that.distanceRange &&
			       this.size == that.size &&
			       this.width == that.width &&
			       this.height == that.height &&
			       Objects.equals(this.yOrigin, that.yOrigin);
		}
		@Override
		public int hashCode(){
			return Objects.hash(type, distanceRange, size, width, height, yOrigin);
		}
	}
	
	public static final class Metrics{
		private final double emSize;
		private final double lineHeight;
		private final double ascender;
		private final double descender;
		private final double underlineY;
		private final double underlineThickness;
		public Metrics(double emSize, double lineHeight, double ascender, double descender, double underlineY, double underlineThickness){
			this.emSize = emSize;
			this.lineHeight = lineHeight;
			this.ascender = ascender;
			this.descender = descender;
			this.underlineY = underlineY;
			this.underlineThickness = underlineThickness;
		}
		public double getEmSize(){
			return emSize;
		}
		public double getLineHeight(){
			return lineHeight;
		}
		public double getAscender(){
			return ascender;
		}
		public double getDescender(){
			return descender;
		}
		public double getUnderlineY(){
			return underlineY;
		}
		public double getUnderlineThickness(){
			return underlineThickness;
		}
		@Override
		public boolean equals(Object obj){
			if(obj == this) return true;
			if(obj == null || obj.getClass() != this.getClass()) return false;
			var that = (Metrics)obj;
			return Double.doubleToLongBits(this.emSize) == Double.doubleToLongBits(that.emSize) &&
			       Double.doubleToLongBits(this.lineHeight) == Double.doubleToLongBits(that.lineHeight) &&
			       Double.doubleToLongBits(this.ascender) == Double.doubleToLongBits(that.ascender) &&
			       Double.doubleToLongBits(this.descender) == Double.doubleToLongBits(that.descender) &&
			       Double.doubleToLongBits(this.underlineY) == Double.doubleToLongBits(that.underlineY) &&
			       Double.doubleToLongBits(this.underlineThickness) == Double.doubleToLongBits(that.underlineThickness);
		}
		@Override
		public int hashCode(){
			return Objects.hash(emSize, lineHeight, ascender, descender, underlineY, underlineThickness);
		}
	}
	
	public static final class Glyph{
		private final int    unicode;
		private final float  advance;
		private final Bounds planeBounds;
		private final Bounds atlasBounds;
		public Glyph(int unicode, float advance, Bounds planeBounds, Bounds atlasBounds){
			this.unicode = unicode;
			this.advance = advance;
			this.planeBounds = planeBounds;
			this.atlasBounds = atlasBounds;
		}
		public int getUnicode(){
			return unicode;
		}
		public float getAdvance(){
			return advance;
		}
		public Bounds getPlaneBounds(){
			return planeBounds;
		}
		public Bounds getAtlasBounds(){
			return atlasBounds;
		}
		
		@Override
		public boolean equals(Object obj){
			if(obj == this) return true;
			if(obj == null || obj.getClass() != this.getClass()) return false;
			var that = (Glyph)obj;
			return this.unicode == that.unicode &&
			       Double.doubleToLongBits(this.advance) == Double.doubleToLongBits(that.advance) &&
			       Objects.equals(this.planeBounds, that.planeBounds);
		}
		@Override
		public int hashCode(){
			return Objects.hash(unicode, advance, planeBounds);
		}
	}
	
	public static final class Bounds{
		private final float left;
		private final float bottom;
		private final float right;
		private final float top;
		public Bounds(float left, float bottom, float right, float top){
			this.left = left;
			this.bottom = bottom;
			this.right = right;
			this.top = top;
		}
		public float getLeft(){
			return left;
		}
		public float getBottom(){
			return bottom;
		}
		public float getRight(){
			return right;
		}
		public float getTop(){
			return top;
		}
		@Override
		public boolean equals(Object obj){
			if(obj == this) return true;
			if(obj == null || obj.getClass() != this.getClass()) return false;
			var that = (Bounds)obj;
			return Double.doubleToLongBits(this.left) == Double.doubleToLongBits(that.left) &&
			       Double.doubleToLongBits(this.bottom) == Double.doubleToLongBits(that.bottom) &&
			       Double.doubleToLongBits(this.right) == Double.doubleToLongBits(that.right) &&
			       Double.doubleToLongBits(this.top) == Double.doubleToLongBits(that.top);
		}
		@Override
		public int hashCode(){
			return Objects.hash(left, bottom, right, top);
		}
	}
	
	public static final class AtlasInfo{
		private final Info        atlas;
		private final Metrics     metrics;
		private final List<Glyph> glyphs;
		private final List<?>     kerning;
		public AtlasInfo(Info atlas, Metrics metrics, List<Glyph> glyphs, List<?> kerning){
			this.atlas = atlas;
			this.metrics = metrics;
			this.glyphs = glyphs;
			this.kerning = kerning;
		}
		public Info getAtlas(){
			return atlas;
		}
		public Metrics getMetrics(){
			return metrics;
		}
		public List<Glyph> getGlyphs(){
			return glyphs;
		}
		public List<?> getKerning(){
			return kerning;
		}
		@Override
		public boolean equals(Object obj){
			if(obj == this) return true;
			if(obj == null || obj.getClass() != this.getClass()) return false;
			var that = (AtlasInfo)obj;
			return Objects.equals(this.atlas, that.atlas) &&
			       Objects.equals(this.metrics, that.metrics) &&
			       Objects.equals(this.glyphs, that.glyphs) &&
			       Objects.equals(this.kerning, that.kerning);
		}
		@Override
		public int hashCode(){
			return Objects.hash(atlas, metrics, glyphs, kerning);
		}
	}
	
	
	private static InputStream binStream(String ttfPath){
		return Objects.requireNonNull(MSDFAtlas.class.getResourceAsStream(ttfPath));
	}
	private static Reader textStream(String ttfPath){
		return new InputStreamReader(binStream(ttfPath));
	}
	
	private final AtlasInfo                                  info;
	private       WeakReference<BufferedImage>               imageRef;
	private final UnsafeSupplier<BufferedImage, IOException> image;
	
	private final Map<Character, Glyph> glyphCache = new HashMap<>();
	
	public MSDFAtlas(String atlasFolder) throws IOException{
		this(atlasFolder, "atlas");
	}
	public MSDFAtlas(String atlasFolder, String atlasName) throws IOException{
		this(() -> binStream(atlasFolder + "/" + atlasName + ".png"), textStream(atlasFolder + "/" + atlasName + ".json"));
	}
	public MSDFAtlas(Supplier<InputStream> png, Reader json) throws IOException{
		png.get().close();
		image = () -> {
			var img = imageRef == null? null : imageRef.get();
			if(img == null){
				img = ImageIO.read(png.get());
				imageRef = new WeakReference<>(img);
			}
			return img;
		};
		info = new GsonBuilder().create().fromJson(json, AtlasInfo.class);
	}
	public AtlasInfo getInfo(){
		return info;
	}
	public BufferedImage getImage(){
		try{
			return image.get();
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	public Glyph getGlyph(int ch){
		return getGlyph((char)ch);
	}
	public Glyph getGlyph(char ch){
		return glyphCache.computeIfAbsent(ch, this::getGlyph0);
	}
	private Glyph getGlyph0(char ch){
		return getGlyphOptional0(ch).orElseGet(
			() -> Stream.of('\uFFFD', '?', ' ')
			            .map(this::getGlyphOptional0)
			            .filter(Optional::isPresent)
			            .map(Optional::get)
			            .findFirst()
			            .orElseThrow()
		);
	}
	public Optional<Glyph> getGlyphOptional(char ch){
		var opt = getGlyphOptional0(ch);
		opt.ifPresent(glyph -> glyphCache.put(ch, glyph));
		return opt;
	}
	private Optional<Glyph> getGlyphOptional0(char ch){
		return info.glyphs.stream().filter(g -> g.getUnicode() == ch).findAny();
	}
}
