/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.netbeans.modules.editor.lib2.view;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.text.AttributeSet;
import javax.swing.text.Position;
import javax.swing.text.Position.Bias;
import javax.swing.text.View;
import org.netbeans.spi.editor.highlighting.HighlightsSequence;


public final class PrependedTextView extends EditorView {

    private final AttributeSet attributes;
    private final EditorView delegate;
    private final TextLayout prependedTextLayout;
    private final ImageIcon inlineIcon;
    private final double prependedTextWidth;
    private final double inlineIconWidth;
    private final double inlineIconHeighOffset;

    public PrependedTextView(DocumentViewOp op, AttributeSet attributes, EditorView delegate) {
        super(null);
        this.attributes = attributes;
        this.delegate = delegate;
        Font font = ViewUtils.getFont(attributes, op.getDefaultHintFont());
        if (attributes.getAttribute(ViewUtils.KEY_VIRTUAL_TEXT_PREPEND) instanceof String prependText) {
            prependedTextLayout = op.createTextLayout(prependText, font);
            // Advance represents the width of the full string, including leading
            // and trailing spaces
            float width = prependedTextLayout.getAdvance();
            // The prependTextWidth is rounded to full char widths, so that layout
            // is not destroyed too much
            double em = op.getDefaultCharWidth();
            prependedTextWidth = Math.ceil(width / em) * em;
        } else {
            prependedTextLayout = null;
            prependedTextWidth = 0;
        }
        if (attributes.getAttribute(ViewUtils.KEY_VIRTUAL_INLINE_ICON) instanceof ImageIcon icon) {
            inlineIcon = icon;
            inlineIconWidth = inlineIcon.getIconWidth();
            inlineIconHeighOffset = (op.getDefaultRowHeight() - inlineIcon.getIconHeight()) / 2;
        } else {
            inlineIcon = null;
            inlineIconWidth = 0;
            inlineIconHeighOffset = 0;
        }
    }

    @Override
    public float getPreferredSpan(int axis) {
        float superSpan = delegate.getPreferredSpan(axis);
        if (axis == View.X_AXIS) {
            superSpan += prependedTextWidth + inlineIconWidth;
        }
        return superSpan;
    }

    @Override
    public AttributeSet getAttributes() {
        return attributes;
    }
    
    @Override
    public Shape modelToViewChecked(int offset, Shape alloc, Bias bias) {
        Shape res = delegate.modelToViewChecked(offset, alloc, bias);
        Rectangle2D rect = ViewUtils.shapeAsRect(res);
        rect.setRect(rect.getX() + prependedTextWidth + inlineIconWidth, rect.getY(), rect.getWidth(), rect.getHeight());
        return rect;
    }

    @Override
    public void paint(Graphics2D g, Shape hViewAlloc, Rectangle clipBounds) {
        Rectangle2D span = ViewUtils.shapeAsRect(hViewAlloc);
        span.setRect(span.getX() + prependedTextWidth + inlineIconWidth, span.getY(), span.getWidth() - prependedTextWidth - inlineIconWidth, span.getHeight());
        delegate.paint(g, span, clipBounds);

        span.setRect(span.getX() - prependedTextWidth - inlineIconWidth, span.getY(), prependedTextWidth + inlineIconWidth, span.getHeight());

        HighlightsSequence highlights = getDocumentView().getPaintHighlights(this, 0);

        if (highlights.moveNext()) {
            AttributeSet attrs = highlights.getAttributes();
            HighlightsViewUtils.fillBackground(g, span, attrs, getDocumentView().getTextComponent());
            HighlightsViewUtils.paintBackgroundHighlights(g, span, attrs, getDocumentView()); //TODO: clear some attributes (like boxes)???
        }

        if (prependedTextLayout != null) {
            g.setColor(Color.gray);
            span.setRect(span.getX(), span.getY(), prependedTextWidth, span.getHeight());
            HighlightsViewUtils.paintTextLayout(g, span, prependedTextLayout, getDocumentView());
        }
        if (inlineIcon != null) {
            g.drawImage(inlineIcon.getImage(), (int) (span.getX() + prependedTextWidth), (int) (span.getY() + inlineIconHeighOffset), inlineIcon.getIconWidth(), inlineIcon.getIconHeight(), null);
        }
    }

    ParagraphView getParagraphView() {
        return (ParagraphView) getParent();
    }

    DocumentView getDocumentView() {
        ParagraphView paragraphView = getParagraphView();
        return (paragraphView != null) ? paragraphView.getDocumentView() : null;
    }

    @Override
    public int getRawEndOffset() {
        return delegate.getRawEndOffset();
    }

    @Override
    public void setRawEndOffset(int offset) {
        delegate.setRawEndOffset(offset);
    }

    @Override
    public int viewToModelChecked(double x, double y, Shape alloc, Position.Bias[] biasReturn) {
        Rectangle2D bounds = ViewUtils.shapeAsRect(alloc);
        bounds.setRect(bounds.getX() + prependedTextWidth, bounds.getY(),
                       bounds.getWidth() - prependedTextWidth, bounds.getHeight());
        if (x <= bounds.getX()) {
            return getStartOffset();
        }
        return delegate.viewToModelChecked(x, y, bounds, biasReturn);
    }

    @Override
    public int getLength() {
        return delegate.getLength();
    }

    @Override
    public int getStartOffset() {
        return delegate.getStartOffset();
    }

    @Override
    public int getEndOffset() {
        return delegate.getEndOffset();
    }

    @Override
    public void setParent(View parent) {
        super.setParent(parent);
        delegate.setParent(parent);
    }

    EditorView getDelegate() {
        return delegate;
    }

}
