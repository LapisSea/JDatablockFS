package demo.photo.ui;

import demo.photo.Texture;

import java.awt.Color;

public class ThumbnailElementSelectable extends ThumbnailElementClickable{
	
	private boolean selected = false;
	
	public ThumbnailElementSelectable(Texture texture){
		this(texture, false);
	}
	
	public ThumbnailElementSelectable(Texture texture, boolean selected){
		super(texture);
		setSelected(selected);
	}
	
	public boolean isSelected(){
		return selected;
	}
	
	public void setSelected(boolean selected){
		this.selected = selected;
		button.setBackground(selected? new Color(170, 255, 170) : new Color(255, 170, 170));
	}
	
	@Override
	protected void onClick(){
		setSelected(!isSelected());
	}
}
