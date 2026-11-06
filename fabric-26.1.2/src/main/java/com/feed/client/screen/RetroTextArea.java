package com.feed.client.screen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;
/** 26.1 builder-based multiline field. */
public final class RetroTextArea {
 public static MultiLineEditBox create(Font font, int x, int y, int w, int h, Component placeholder, Component message) {
  return MultiLineEditBox.builder().setX(x).setY(y).setPlaceholder(placeholder).setTextColor(RetroUi.FG).setTextShadow(false).setCursorColor(RetroUi.FG).build(font,w,h,message);
 }
 private RetroTextArea() {}
}
