/* -*- mode: jde; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  PPolygon - zbuffer polygon rendering object for BGraphics
  Part of the Processing project - http://Proce55ing.net

  Copyright (c) 2001-03 
  Ben Fry, Massachusetts Institute of Technology and 
  Casey Reas, Interaction Design Institute Ivrea

  This library is free software; you can redistribute it and/or
  modify it under the terms of the GNU Lesser General Public
  License as published by the Free Software Foundation; either
  version 2.1 of the License, or (at your option) any later version.

  This library is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
  Lesser General Public License for more details.

  You should have received a copy of the GNU Lesser General 
  Public License along with this library; if not, write to the 
  Free Software Foundation, Inc., 59 Temple Place, Suite 330, 
  Boston, MA  02111-1307  USA
*/

package processing.core;


public class PPolygon implements PConstants {
  static final int DEFAULT_SIZE = 64; // this is needed for spheres
  float vertices[][] = new float[DEFAULT_SIZE][VERTEX_FIELD_COUNT];
  int vertexCount;

  // really this is "debug" but.. 
  static final boolean FRY = false;

  // after some fiddling, this seems to produce the best results
  static final int ZBUFFER_MIN_COVERAGE = 204;

  float r[]   = new float[DEFAULT_SIZE]; // storage used by incrementalize
  float dr[]  = new float[DEFAULT_SIZE];
  float l[]   = new float[DEFAULT_SIZE]; // more storage for incrementalize
  float dl[]  = new float[DEFAULT_SIZE];
  float sp[]  = new float[DEFAULT_SIZE]; // temporary storage for scanline
  float sdp[] = new float[DEFAULT_SIZE];

  // color and xyz are always interpolated
  boolean interpX;
  boolean interpZ;
  boolean interpUV; // is this necessary? could just check timage != null
  boolean interpRGBA;

  int rgba;
  int r2, g2, b2, a2, a2orig;

  float zbuffer[];
  boolean noDepthTest;

  PGraphics parent;
  int pixels[];

  // the parent's width/height, 
  // or if smoothing is enabled, parent's w/h scaled
  // up by the smoothing dimension
  int width, height; 
  int width1, height1;

  PImage timage;
  int tpixels[];
  int theight, twidth;
  int theight1, twidth1;
  int tformat;

  // temp fix to behave like SMOOTH_IMAGES
  boolean texture_smooth;

  // for anti-aliasing
  static final int SUBXRES  = 8;
  static final int SUBXRES1 = 7;
  static final int SUBYRES  = 8;
  static final int SUBYRES1 = 7;
  static final int MAX_COVERAGE = SUBXRES * SUBYRES;

  boolean smoothing;
  int firstModY;
  int lastModY;
  int lastY;
  int aaleft[] = new int[SUBYRES];
  int aaright[] = new int[SUBYRES];
  int aaleftmin, aarightmin;
  int aaleftmax, aarightmax;
  int aaleftfull, aarightfull;

  final private int MODYRES(int y) {
    return (y & SUBYRES1);
  }


  public PPolygon(PGraphics iparent) {
    parent = iparent;
    reset(0);
  }


  public void reset(int count) {
    vertexCount = count;
    interpX = true;
    interpZ = true;
    interpUV = false;
    interpRGBA = true;
    timage = null;
  }


  public float[] nextVertex() {
    if (vertexCount == vertices.length) {
      //parent.message(CHATTER, "re-allocating for " + 
      //             (vertexCount*2) + " vertices");
      float temp[][] = new float[vertexCount<<1][VERTEX_FIELD_COUNT];
      System.arraycopy(vertices, 0, temp, 0, vertexCount);
      vertices = temp;

      r   = new float[vertices.length];
      dr  = new float[vertices.length];
      l   = new float[vertices.length];
      dl  = new float[vertices.length];
      sp  = new float[vertices.length];
      sdp = new float[vertices.length];
    }
    return vertices[vertexCount++];  // returns v[0], sets vc to 1
  }


  /**
   * Return true if this vertex is redundant. If so, will also
   * decrement the vertex count.
   */
  public boolean redundantVertex(float x, float y, float z) {
    // because vertexCount will be 2 when setting vertex[1]
    if (vertexCount < 2) return false;

    // vertexCount-1 is the current vertex that would be used
    // vertexCount-2 would be the previous feller
    if ((Math.abs(vertices[vertexCount-2][MX] - x) < 0.0001f) &&
        (Math.abs(vertices[vertexCount-2][MY] - y) < 0.0001f) &&
        (Math.abs(vertices[vertexCount-2][MZ] - z) < 0.0001f)) {
      vertexCount--;
      return true;
    }
    return false;
  }


  public void texture(PImage image) {
    this.timage = image;
    this.tpixels = image.pixels;
    this.twidth = image.width;
    this.theight = image.height;
    this.tformat = image.format;

    twidth1 = twidth - 1;
    theight1 = theight - 1;
    interpUV = true;
  }


  public void render() {
    if (vertexCount < 3) return;

    // these may have changed due to a resize()
    // so they should be refreshed here
    pixels = parent.pixels;
    zbuffer = parent.zbuffer;

    noDepthTest = parent.hints[NO_DEPTH_TEST]; //!parent.depthTest;
    smoothing = parent.smooth;

    // by default, text turns on smoothing for the textures
    // themselves. but this should be shut off if the hint
    // for DISABLE_TEXT_SMOOTH is set. 
    texture_smooth = (parent.drawing_text && 
                      !parent.hints[DISABLE_TEXT_SMOOTH]);

    width = smoothing ? parent.width*SUBXRES : parent.width;
    height = smoothing ? parent.height*SUBYRES : parent.height;

    width1 = width - 1;
    height1 = height - 1;

    if (!interpRGBA) {
      r2 = (int) (vertices[0][R] * 255);
      g2 = (int) (vertices[0][G] * 255);
      b2 = (int) (vertices[0][B] * 255);
      a2 = (int) (vertices[0][A] * 255);
      a2orig = a2; // save an extra copy
      rgba = 0xff000000 | (r2 << 16) | (g2 << 8) | b2;
    }

    for (int i = 0; i < vertexCount; i++) {
      r[i] = 0; dr[i] = 0; l[i] = 0; dl[i] = 0; 
    }

    if (smoothing) {
      for (int i = 0; i < vertexCount; i++) {
        vertices[i][X] *= SUBXRES;
        vertices[i][Y] *= SUBYRES;
      }
      firstModY = -1;
    }

    // find top vertex (y is zero at top, higher downwards)
    int topi = 0;
    float ymin = vertices[0][Y];
    float ymax = vertices[0][Y]; // fry 031001
    for (int i = 1; i < vertexCount; i++) {
      if (vertices[i][Y] < ymin) {
        ymin = vertices[i][Y];
        topi = i;
      }
      if (vertices[i][Y] > ymax) ymax = vertices[i][Y];
    }

    // the last row is an exceptional case, because there won't
    // necessarily be 8 rows of subpixel lines that will force
    // the final line to render. so instead, the algo keeps track
    // of the lastY (in subpixel resolution) that will be rendered
    // and that will force a scanline to happen the same as 
    // every eighth in the other situations
    //lastY = -1;  // fry 031001
    lastY = (int) (ymax - 0.5f);  // global to class bc used by other fxns

    int lefti = topi;             // li, index of left vertex 
    int righti = topi;            // ri, index of right vertex
    int y = (int) (ymin + 0.5f);  // current scan line
    int lefty = y - 1;            // lower end of left edge
    int righty = y - 1;           // lower end of right edge

    interpX = true;

    int remaining = vertexCount;

    // scan in y, activating new edges on left & right
    // as scan line passes over new vertices
    while (remaining > 0) {
      // advance left edge?
      while ((lefty <= y) && (remaining > 0)) {
        remaining--;
        // step ccw down left side
        int i = (lefti != 0) ? (lefti-1) : (vertexCount-1);
        incrementalize_y(vertices[lefti], vertices[i], l, dl, y);
        lefty = (int) (vertices[i][Y] + 0.5f);
        lefti = i;
      }

      // advance right edge?
      while ((righty <= y) && (remaining > 0)) {
        remaining--;                            
        // step cw down right edge
        int i = (righti != vertexCount-1) ? (righti + 1) : 0;
        incrementalize_y(vertices[righti], vertices[i], r, dr, y);
        righty = (int) (vertices[i][Y] + 0.5f);
        righti = i;
      }

      // do scanlines till end of l or r edge
      while (y < lefty && y < righty) {
        // this doesn't work because it's not always set here
        //if (remaining == 0) {
        //lastY = (lefty < righty) ? lefty-1 : righty-1;
        //System.out.println("lastY is " + lastY);
        //}

        if ((y >= 0) && (y < height)) {
          //try {  // hopefully this bug is fixed
          if (l[X] <= r[X]) scanline(y, l, r);
          else scanline(y, r, l);
          //} catch (ArrayIndexOutOfBoundsException e) {
          //e.printStackTrace();
          //}
        }
        y++;
        // this increment probably needs to be different
        // UV and RGB shouldn't be incremented until line is emitted
        increment(l, dl);
        increment(r, dr);
      }
    }
    //if (smoothing) {
    //System.out.println("y/lasty/lastmody = " + y + " " + lastY + " " + lastModY);
    //}
  }


  public void unexpand() {
    if (smoothing) {
      for (int i = 0; i < vertexCount; i++) {
        vertices[i][X] /= SUBXRES;
        vertices[i][Y] /= SUBYRES;
      }
    }
  }


  private void scanline(int y, float l[], float r[]) {
    //System.out.println("scanline " + y);
    for (int i = 0; i < vertexCount; i++) {  // should be moved later
      sp[i] = 0; sdp[i] = 0;
    }

    // this rounding doesn't seem to be relevant with smoothing
    int lx = (int) (l[X] + 0.49999f);  // ceil(l[X]-.5);
    if (lx < 0) lx = 0;
    int rx = (int) (r[X] - 0.5f);
    if (rx > width1) rx = width1;

    if (lx > rx) return;

    if (smoothing) {
      int mody = MODYRES(y);

      aaleft[mody] = lx;
      aaright[mody] = rx;

      if (firstModY == -1) {
        firstModY = mody;
        aaleftmin = lx; aaleftmax = lx;
        aarightmin = rx; aarightmax = rx;

      } else {
        if (aaleftmin > aaleft[mody]) aaleftmin = aaleft[mody];
        if (aaleftmax < aaleft[mody]) aaleftmax = aaleft[mody];
        if (aarightmin > aaright[mody]) aarightmin = aaright[mody];
        if (aarightmax < aaright[mody]) aarightmax = aaright[mody];
      }

      lastModY = mody;  // moved up here (before the return) 031001
      // not the eighth (or lastY) line, so not scanning this time
      if ((mody != SUBYRES1) && (y != lastY)) return;
        //lastModY = mody;  // eeK! this was missing
      //return;

      //if (y == lastY) {
      //System.out.println("y is lasty");
      //}
      //lastModY = mody;
      aaleftfull = aaleftmax/SUBXRES + 1;
      aarightfull = aarightmin/SUBXRES - 1;
    }

    // this is the setup, based on lx
    incrementalize_x(l, r, sp, sdp, lx);

    // scan in x, generating pixels
    // using parent.width to get actual pixel index
    // rather than scaled by smoothing factor
    int offset = smoothing ? parent.width * (y / SUBYRES) : parent.width*y;

    int truelx = 0, truerx = 0;
    if (smoothing) {
      truelx = lx / SUBXRES;
      truerx = (rx + SUBXRES1) / SUBXRES;

      lx = aaleftmin / SUBXRES;
      rx = (aarightmax + SUBXRES1) / SUBXRES;
      if (lx < 0) lx = 0;
      if (rx > parent.width1) rx = parent.width1;
    }

    interpX = false;
    int tr, tg, tb, ta;

    for (int x = lx; x <= rx; x++) {
      // added == because things on same plane weren't replacing each other
      // makes for strangeness in 3D, but totally necessary for 2D
      if (noDepthTest || (sp[Z] <= zbuffer[offset+x])) {

        // map texture based on U, V coords in sp[U] and sp[V]
        if (interpUV) {
          int tu = (int)sp[U]; 
          int tv = (int)sp[V]; 

          if (tu > twidth1) tu = twidth1;
          if (tv > theight1) tv = theight1;
          if (tu < 0) tu = 0;
          if (tv < 0) tv = 0;

          int txy = tv*twidth + tu;

          if (smoothing || texture_smooth) {
            //if (FRY) System.out.println("sp u v = " + sp[U] + " " + sp[V]);
            //System.out.println("sp u v = " + sp[U] + " " + sp[V]);
            // tuf1/tvf1 is the amount of coverage for the adjacent
            // pixel, which is the decimal percentage.
            int tuf1 = (int) (255f * (sp[U] - (float)tu));
            int tvf1 = (int) (255f * (sp[V] - (float)tv));

            // the closer sp[U or V] is to the decimal being zero
            // the more coverage it should get of the original pixel
            int tuf = 255 - tuf1;
            int tvf = 255 - tvf1;

            // this code sucks! filled with bugs and slow as hell!
            int pixel00 = tpixels[txy];
            int pixel01 = (tv < theight1) ?
              tpixels[txy + twidth] : tpixels[txy];
            int pixel10 = (tu < twidth1) ?
              tpixels[txy + 1] : tpixels[txy];
            int pixel11 = ((tv < theight1) && (tu < twidth1)) ?
              tpixels[txy + twidth + 1] : tpixels[txy];

            int p00, p01, p10, p11;
            int px0, px1, pxy;

            if (tformat == ALPHA) {
              px0 = (pixel00*tuf + pixel10*tuf1) >> 8;
              px1 = (pixel01*tuf + pixel11*tuf1) >> 8;
              ta = (((px0*tvf + px1*tvf1) >> 8) *
                    (interpRGBA ? ((int) (sp[A]*255)) : a2orig)) >> 8;

            } else if (tformat == RGBA) {
              p00 = (pixel00 >> 24) & 0xff;
              p01 = (pixel01 >> 24) & 0xff;
              p10 = (pixel10 >> 24) & 0xff;
              p11 = (pixel11 >> 24) & 0xff;

              px0 = (p00*tuf + p10*tuf1) >> 8;
              px1 = (p01*tuf + p11*tuf1) >> 8;
              ta = (((px0*tvf + px1*tvf1) >> 8) *
                    (interpRGBA ? ((int) (sp[A]*255)) : a2orig)) >> 8;

            } else {  // RGB image, no alpha
              ta = interpRGBA ? ((int) (sp[A]*255)) : a2orig;
            }

            if ((tformat == RGB) || (tformat == RGBA)) {
              p00 = (pixel00 >> 16) & 0xff;  // red
              p01 = (pixel01 >> 16) & 0xff;
              p10 = (pixel10 >> 16) & 0xff;
              p11 = (pixel11 >> 16) & 0xff;

              px0 = (p00*tuf + p10*tuf1) >> 8;
              px1 = (p01*tuf + p11*tuf1) >> 8;
              tr = (((px0*tvf + px1*tvf1) >> 8) * 
                    (interpRGBA ? ((int) sp[R]*255) : r2)) >> 8;


              p00 = (pixel00 >> 8) & 0xff;  // green
              p01 = (pixel01 >> 8) & 0xff;
              p10 = (pixel10 >> 8) & 0xff;
              p11 = (pixel11 >> 8) & 0xff;

              px0 = (p00*tuf + p10*tuf1) >> 8;
              px1 = (p01*tuf + p11*tuf1) >> 8;
              tg = (((px0*tvf + px1*tvf1) >> 8) * 
                    (interpRGBA ? ((int) sp[G]*255) : g2)) >> 8;


              p00 = pixel00 & 0xff;  // blue
              p01 = pixel01 & 0xff;
              p10 = pixel10 & 0xff;
              p11 = pixel11 & 0xff;

              px0 = (p00*tuf + p10*tuf1) >> 8;
              px1 = (p01*tuf + p11*tuf1) >> 8;
              tb = (((px0*tvf + px1*tvf1) >> 8) * 
                    (interpRGBA ? ((int) sp[B]*255) : b2)) >> 8;

            } else {  // alpha image, only use current fill color
              if (interpRGBA) {
                tr = (int) (sp[R] * 255);
                tg = (int) (sp[G] * 255);
                tb = (int) (sp[B] * 255);

              } else {
                tr = r2;
                tg = g2;
                tb = b2;
              }
            }

            // get coverage for pixel if smoothing
            // checks smoothing again here because of 
            // hints[SMOOTH_IMAGES] used up above
            int weight = smoothing ? coverage(x) : 255;
            if (weight != 255) ta = ta*weight >> 8;

          } else {  // no smoothing, just get the pixels
            int tpixel = tpixels[txy];

            // TODO i doubt splitting these guys really gets us
            // all that much speed.. is it worth it?
            if (tformat == ALPHA) {
              ta = tpixel;

              if (interpRGBA) {
                tr = (int) sp[R]*255;
                tg = (int) sp[G]*255;
                tb = (int) sp[B]*255;
                if (sp[A] != 1) {
                  ta = (((int) sp[A]*255) * ta) >> 8;
                }

              } else {
                tr = r2;
                tg = g2;
                tb = b2;
                ta = (a2orig * ta) >> 8;
              }

            } else {  // RGB or RGBA
              ta = (tformat == RGB) ? 255 : (tpixel >> 24) & 0xff;

              if (interpRGBA) {
                tr = (((int) sp[R]*255) * ((tpixel >> 16) & 0xff)) >> 8;
                tg = (((int) sp[G]*255) * ((tpixel >> 8) & 0xff)) >> 8;
                tb = (((int) sp[B]*255) * ((tpixel) & 0xff)) >> 8;
                ta = (((int) sp[A]*255) * ta) >> 8;

              } else {
                tr = (r2 * ((tpixel >> 16) & 0xff)) >> 8;
                tg = (g2 * ((tpixel >> 8) & 0xff)) >> 8;
                tb = (b2 * ((tpixel) & 0xff)) >> 8;
                ta = (a2orig * ta) >> 8;
              }
            }
          }

          if ((ta == 254) || (ta == 255)) {  // if (ta & 0xf8) would be good
            // no need to blend
            pixels[offset+x] = 0xff000000 | (tr << 16) | (tg << 8) | tb;
            zbuffer[offset+x] = sp[Z];

          } else {
            // blend with pixel on screen
            int a1 = 255-ta;
            int r1 = (pixels[offset+x] >> 16) & 0xff;
            int g1 = (pixels[offset+x] >> 8) & 0xff;
            int b1 = (pixels[offset+x]) & 0xff;

            pixels[offset+x] = 0xff000000 | 
              (((tr*ta + r1*a1) >> 8) << 16) |
              ((tg*ta + g1*a1) & 0xff00) |
              ((tb*ta + b1*a1) >> 8);
            if (ta > ZBUFFER_MIN_COVERAGE) zbuffer[offset+x] = sp[Z];
          }

        } else {  // no image applied
          int weight = smoothing ? coverage(x) : 255;

          if (interpRGBA) {
            r2 = (int) (sp[R] * 255);
            g2 = (int) (sp[G] * 255);
            b2 = (int) (sp[B] * 255);
            if (sp[A] != 1) weight = (weight * ((int) (sp[A] * 255))) >> 8;
            if (weight == 255) {
              rgba = 0xff000000 | (r2 << 16) | (g2 << 8) | b2;
            }
          } else {
            if (a2orig != 255) weight = (weight * a2orig) >> 8;
          }

          if (weight == 255) {
            // no blend, no aa, just the rgba
            pixels[offset+x] = rgba;
            zbuffer[offset+x] = sp[Z];

          } else {
            int r1 = (pixels[offset+x] >> 16) & 0xff;
            int g1 = (pixels[offset+x] >> 8) & 0xff;
            int b1 = (pixels[offset+x]) & 0xff;
            a2 = weight;

            int a1 = 255 - a2;
            pixels[offset+x] = (0xff000000 |
                                ((r1*a1 + r2*a2) >> 8) << 16 |
                                // use & instead of >> and << below
                                ((g1*a1 + g2*a2) >> 8) << 8 | 
                                ((b1*a1 + b2*a2) >> 8));

            if (a2 > ZBUFFER_MIN_COVERAGE) zbuffer[offset+x] = sp[Z];
          }
        }
      }
      // if smoothing enabled, don't increment values
      // for the pixel in the stretch out version 
      // of the scanline used to get smooth edges.
      if (!smoothing || ((x >= truelx) && (x <= truerx))) {
        increment(sp, sdp);
      }
    }
    firstModY = -1;
    interpX = true;
  }


  // x is in screen, not huge 8x coordinates
  private int coverage(int x) {
    if ((x >= aaleftfull) && (x <= aarightfull) &&
        // important since not all SUBYRES lines may have been covered
        (firstModY == 0) && (lastModY == SUBYRES1)) {
      return 255;
    }

    int pixelLeft = x*SUBXRES;  // huh?
    int pixelRight = pixelLeft + 8;

    int amt = 0;
    for (int i = firstModY; i <= lastModY; i++) {
      if ((aaleft[i] > pixelRight) || (aaright[i] < pixelLeft)) {
        continue;
      }
      // does this need a +1 ?
      amt += ((aaright[i] < pixelRight ? aaright[i] : pixelRight) - 
              (aaleft[i] > pixelLeft ? aaleft[i] : pixelLeft));
    }
    amt <<= 2;
    return (amt == 256) ? 255 : amt;
  }


  private void incrementalize_y(float p1[], float p2[],
                                float p[], float dp[], int y) {
    float delta = p2[Y] - p1[Y];
    if (delta == 0) delta = ONE;
    float fraction = y + HALF - p1[Y];

    if (interpX) {
      dp[X] = (p2[X] - p1[X]) / delta;
      p[X] = p1[X] + dp[X] * fraction;
    }
    if (interpZ) {
      dp[Z] = (p2[Z] - p1[Z]) / delta;
      p[Z] = p1[Z] + dp[Z] * fraction;
    }

    if (interpRGBA) {
      dp[R] = (p2[R] - p1[R]) / delta;
      dp[G] = (p2[G] - p1[G]) / delta;
      dp[B] = (p2[B] - p1[B]) / delta;
      dp[A] = (p2[A] - p1[A]) / delta;
      p[R] = p1[R] + dp[R] * fraction;
      p[G] = p1[G] + dp[G] * fraction;
      p[B] = p1[B] + dp[B] * fraction;
      p[A] = p1[A] + dp[A] * fraction;
    }

    if (interpUV) {
      dp[U] = (p2[U] - p1[U]) / delta;
      dp[V] = (p2[V] - p1[V]) / delta;

      //if (smoothing) {
      //p[U] = p1[U]; //+ dp[U] * fraction;
      //p[V] = p1[V]; //+ dp[V] * fraction;

      //} else {
      p[U] = p1[U] + dp[U] * fraction;
      p[V] = p1[V] + dp[V] * fraction;
      //}
      if (FRY) System.out.println("inc y p[U] p[V] = " + p[U] + " " + p[V]);
    }
  }


  private void incrementalize_x(float p1[], float p2[],
                                float p[], float dp[], int x) {
    float delta = p2[X] - p1[X];
    if (delta == 0) delta = ONE;
    float fraction = x + HALF - p1[X];
    if (smoothing) {
      delta /= SUBXRES; 
      fraction /= SUBXRES;
    }

    if (interpX) {
      dp[X] = (p2[X] - p1[X]) / delta;
      p[X] = p1[X] + dp[X] * fraction;
    }
    if (interpZ) {
      dp[Z] = (p2[Z] - p1[Z]) / delta;
      p[Z] = p1[Z] + dp[Z] * fraction;
    }

    if (interpRGBA) {
      dp[R] = (p2[R] - p1[R]) / delta;
      dp[G] = (p2[G] - p1[G]) / delta;
      dp[B] = (p2[B] - p1[B]) / delta;
      dp[A] = (p2[A] - p1[A]) / delta;
      p[R] = p1[R] + dp[R] * fraction;
      p[G] = p1[G] + dp[G] * fraction;
      p[B] = p1[B] + dp[B] * fraction;
      p[A] = p1[A] + dp[A] * fraction;
    }

    if (interpUV) {
      if (FRY) System.out.println("delta, frac = " + delta + ", " + fraction);
      dp[U] = (p2[U] - p1[U]) / delta;
      dp[V] = (p2[V] - p1[V]) / delta;

      //if (smoothing) {
      //p[U] = p1[U];
        // offset for the damage that will be done by the
        // 8 consecutive calls to scanline
        // agh.. this won't work b/c not always 8 calls before render
        // maybe lastModY - firstModY + 1 instead?
      if (FRY) System.out.println("before inc x p[V] = " + p[V] + " " + p1[V] + " " + p2[V]);
      //p[V] = p1[V] - SUBXRES1 * fraction;

      //} else {
      p[U] = p1[U] + dp[U] * fraction;
      p[V] = p1[V] + dp[V] * fraction;
      //}
    }
  }


  private void increment(float p[], float dp[]) {
    if (interpX) p[X] += dp[X];
    if (interpZ) p[Z] += dp[Z];

    if (interpRGBA) {
      p[R] += dp[R];
      p[G] += dp[G];
      p[B] += dp[B];
      p[A] += dp[A];
    }

    if (interpUV) {
      if (FRY) System.out.println("increment() " + p[V] + " " + dp[V]);
      p[U] += dp[U];
      p[V] += dp[V];
    }
  }
}
