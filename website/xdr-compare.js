(()=>{
  "use strict";

  const SOURCE_IMAGE="assets/camx-dynamic-range-master.jpg";

  const vertexSource=`
    attribute vec2 aPosition;
    varying vec2 vUv;
    void main(){
      vUv=aPosition*.5+.5;
      gl_Position=vec4(aPosition,0.0,1.0);
    }
  `;

  const fragmentSource=`
    precision highp float;
    uniform sampler2D uSource;
    uniform vec2 uResolution;
    uniform vec2 uTextureSize;
    uniform float uSplit;
    uniform float uPreset;
    varying vec2 vUv;

    const vec3 REC2020_LUMA=vec3(.2627,.6780,.0593);
    const float DIFFUSE_WHITE_NITS=203.0;

    float luma2020(vec3 color){return dot(color,REC2020_LUMA);}
    vec3 toLinear(vec3 color){
      vec3 low=color/12.92;
      vec3 high=pow((color+.055)/1.055,vec3(2.4));
      return mix(low,high,step(vec3(.04045),color));
    }
    vec3 toDisplay(vec3 color){
      color=clamp(color,0.0,1.0);
      vec3 low=color*12.92;
      vec3 high=1.055*pow(color,vec3(1.0/2.4))-.055;
      return mix(low,high,step(vec3(.0031308),color));
    }

    vec2 coverUv(vec2 uv){
      float viewportAspect=uResolution.x/max(uResolution.y,1.0);
      float sourceAspect=uTextureSize.x/max(uTextureSize.y,1.0);
      vec2 scale=vec2(1.0);
      if(viewportAspect>sourceAspect)scale.y=sourceAspect/viewportAspect;
      else scale.x=viewportAspect/sourceAspect;
      return (uv-.5)*scale+.5;
    }

    vec3 sampleLinear(vec2 uv){
      return toLinear(texture2D(uSource,clamp(uv,vec2(.001),vec2(.999))).rgb);
    }

    float signalShape(float sceneNorm,float signalGamma,float toeWidth,float toeAmount){
      float x=max(sceneNorm,0.0);
      if(x<=0.0)return 0.0;
      if(x<=1.0){
        float toe=smoothstep(0.0,toeWidth,x)*toeAmount*(1.0-x);
        return pow(x,signalGamma)+toe;
      }
      return 1.0+(signalGamma-toeAmount)*(x-1.0);
    }

    float profileMidtones(float signal,float shadowLift,float midtoneContrast){
      float x=max(signal,0.0);
      if(x<=0.0||x>=1.0)return x;
      float liftWindow=smoothstep(.025,.20,x)*(1.0-smoothstep(.78,1.20,x));
      x+=shadowLift*sqrt(x)*(1.0-x)*liftWindow;
      float contrastWindow=smoothstep(.055,.24,x)*(1.0-smoothstep(.76,1.0,x));
      float signedContrast=(x-.5)*4.0*x*(1.0-x);
      return max(x+midtoneContrast*signedContrast*contrastWindow,0.0);
    }

    float blackAnchoredLift(float y,float amount){
      float window=smoothstep(.008,.095,y)*(1.0-smoothstep(.52,.82,y));
      return y+amount*sqrt(max(y,0.0))*(1.0-y)*window;
    }

    float pivotContrast(float y,float amount,float pivot){
      return clamp(y+amount*(y-pivot)*4.0*y*(1.0-y),0.0,1.0);
    }

    float highlightShoulder(float y,float knee,float strength){
      if(y<=knee)return y;
      float range=max(1.0-knee,1e-4);
      float normalized=(y-knee)/range;
      float compressed=(1.0-exp(-normalized*strength))/(1.0-exp(-strength));
      return clamp(knee+range*compressed,0.0,1.0);
    }

    vec3 detailSafe(vec3 color,vec3 source,vec3 blur,float strength){
      vec3 highPass=source-blur;
      float edge=max(max(abs(highPass.r),abs(highPass.g)),abs(highPass.b));
      float haloGuard=1.0-smoothstep(.055,.20,edge);
      float noiseGuard=smoothstep(.015,.05,luma2020(source))*(1.0-smoothstep(.82,1.02,luma2020(source)));
      return clamp(color+highPass*strength*haloGuard*noiseGuard,0.0,1.0);
    }

    vec3 reduceSdrNoise(vec3 source,vec3 blur,float strength){
      float y=luma2020(source);
      vec3 delta=source-blur;
      float lumaDelta=abs(luma2020(delta));
      float chromaDelta=length(delta-vec3(luma2020(delta)));
      float flatLuma=1.0-smoothstep(.004,.042,lumaDelta);
      float chromaGuard=1.0-smoothstep(.018,.105,chromaDelta);
      float shadowAuthority=mix(.62,1.0,1.0-smoothstep(.08,.42,y));
      float highlightGuard=1.0-smoothstep(.78,.98,y);
      float amount=strength*flatLuma*chromaGuard*shadowAuthority*highlightGuard;
      return mix(source,blur,clamp(amount,0.0,.72));
    }

    vec3 restoreOpticalDetail(vec3 color,vec3 source,vec3 blur,float strength){
      vec3 highPass=source-blur;
      float edge=abs(luma2020(highPass));
      float detailConfidence=smoothstep(.010,.038,edge)*(1.0-smoothstep(.10,.22,edge));
      float tonalWindow=smoothstep(.025,.14,luma2020(source))*(1.0-smoothstep(.80,.98,luma2020(source)));
      return clamp(color+highPass*strength*detailConfidence*tonalWindow,0.0,1.0);
    }

    vec3 rec709ToRec2020(vec3 color){
      return max(vec3(
        dot(color,vec3(.6274,.3293,.0433)),
        dot(color,vec3(.0691,.9195,.0114)),
        dot(color,vec3(.0164,.0880,.8956))
      ),vec3(0.0));
    }

    vec3 rec2020ToRec709(vec3 color){
      return vec3(
        dot(color,vec3(1.6605,-.5876,-.0728)),
        dot(color,vec3(-.1246,1.1329,-.0083)),
        dot(color,vec3(-.0182,-.1006,1.1187))
      );
    }

    vec3 rotateHueNeutralAxis(vec3 color,float degreesValue){
      float angle=radians(degreesValue);
      float cs=cos(angle);
      float sn=sin(angle);
      vec3 axis=vec3(.5773502692);
      return color*cs+cross(axis,color)*sn+axis*dot(axis,color)*(1.0-cs);
    }

    vec3 applyMeasuredProfileCorrection(vec3 source,bool vision){
      source=max(source,vec3(0.0));
      float originalY=max(luma2020(source),1e-7);
      vec3 channelScale=vision?vec3(.974587,.991713,.991713):vec3(1.0);
      source*=channelScale;
      source*=clamp(originalY/max(luma2020(source),1e-7),.85,1.15);

      float rg=source.r/max(source.g,1e-7);
      float rb=source.r/max(source.b,1e-7);
      float bg=source.b/max(source.g,1e-7);
      float skin=smoothstep(1.04,1.24,rg)*(1.0-smoothstep(1.70,2.25,rg))*
        smoothstep(1.02,1.32,rb)*smoothstep(.035,.16,originalY)*
        (1.0-smoothstep(.75,1.10,originalY));
      float sky=smoothstep(1.02,1.24,bg)*(1.0-smoothstep(1.00,1.28,rg))*
        smoothstep(.045,.20,originalY)*(1.0-smoothstep(1.00,1.45,originalY));
      float hueDegrees=(vision?2.493:2.567)*skin+(vision?8.0:6.999)*sky;
      if(abs(hueDegrees)>1e-5){
        source=rotateHueNeutralAxis(source,hueDegrees);
        source*=clamp(originalY/max(luma2020(source),1e-7),.85,1.15);
      }
      return max(source,vec3(0.0));
    }

    vec3 profileChromaCharacter(vec3 color,float baseBoost,float skyBonusStrength){
      float y=max(luma2020(color),1e-7);
      float peak=max(color.r,max(color.g,color.b));
      float floorValue=min(color.r,min(color.g,color.b));
      float relativeChroma=(peak-floorValue)/max(y,.04);
      float colorConfidence=smoothstep(.035,.22,relativeChroma);
      float rg=color.r/max(color.g,1e-6);
      float bg=color.b/max(color.g,1e-6);
      float skin=smoothstep(1.02,1.16,rg)*(1.0-smoothstep(1.58,2.10,rg))*
        (1.0-smoothstep(.98,1.18,bg));
      float sky=smoothstep(1.03,1.22,bg)*(1.0-smoothstep(1.0,1.22,rg));
      float tonalWindow=smoothstep(.035,.16,y)*(1.0-smoothstep(.88,1.65,y));
      float gain=1.0+(baseBoost+sky*skyBonusStrength)*colorConfidence*tonalWindow*mix(1.0,.45,skin);
      return vec3(y)+(color-vec3(y))*gain;
    }

    vec3 compressRec709GamutPreserveLuma(vec3 color){
      float peak=max(color.r,max(color.g,color.b));
      if(peak<=1.0)return max(color,vec3(0.0));
      float y=clamp(dot(color,vec3(.2126,.7152,.0722)),0.0,1.0);
      float chromaSpan=peak-y;
      if(chromaSpan<=1e-7)return vec3(y);
      float chromaScale=clamp((1.0-y)/chromaSpan,0.0,1.0);
      return max(vec3(y)+(color-vec3(y))*chromaScale,vec3(0.0));
    }

    vec3 applyCamxProfile(vec3 source,vec3 blur,bool vision){
      float signalGamma=vision?.800034:.859937;
      float toeWidth=vision?.289876:.238882;
      float toeAmount=vision?.031033:.024780;
      float shadowLift=vision?.232156:.167837;
      float midtoneContrast=vision?.174135:.100943;
      float chromaBoost=vision?.091565:.038361;
      float skyBonus=vision?.080000:.062392;
      float localDetail=vision?.600000:.400000;
      float highlightDesat=vision?.128953:.149718;

      vec3 clean709=reduceSdrNoise(source,blur,vision?.58:.46);
      vec3 source2020=applyMeasuredProfileCorrection(rec709ToRec2020(clean709),vision);
      float sourceY=max(luma2020(source2020),1e-7);

      // The coded profile is exposure-normalized around the JPEG's 50% diffuse proxy. This keeps the
      // project gamma/toe/lift/contrast constants but avoids treating an already-rendered SDR image as RAW.
      float codedSignal=signalShape(sourceY/.50,signalGamma,toeWidth,toeAmount)*.50;
      codedSignal=profileMidtones(codedSignal,shadowLift,midtoneContrast);
      codedSignal=highlightShoulder(codedSignal,vision?.76:.72,vision?.92:1.08);
      float mappedY=mix(sourceY,codedSignal,vision?.34:.24);
      mappedY=pivotContrast(mappedY,vision?.040:.025,.42);
      mappedY=clamp(mappedY,0.0,1.0);

      vec3 color=source2020*(mappedY/sourceY);
      color=profileChromaCharacter(color,chromaBoost,skyBonus);
      float colorY=max(luma2020(color),1e-7);
      float desat=clamp(highlightDesat*smoothstep(.55,1.0,colorY),0.0,highlightDesat);
      color+= (vec3(colorY)-color)*desat;

      vec3 display709=compressRec709GamutPreserveLuma(rec2020ToRec709(color));
      return restoreOpticalDetail(display709,clean709,blur,localDetail*(vision?.58:.52));
    }

    vec3 applyHdr10Reference(vec3 source,vec3 blur){
      vec3 clean=reduceSdrNoise(source,blur,.24);
      float y=max(luma2020(clean),1e-5);
      float mappedY=blackAnchoredLift(y,.055);
      mappedY=pivotContrast(mappedY,.115,.43);
      mappedY=highlightShoulder(mappedY,.64,1.72);
      vec3 color=clean*(mappedY/y);
      color=vec3(mappedY)+(color-vec3(mappedY))*.96;
      return restoreOpticalDetail(color,clean,blur,.10);
    }

    vec3 applyDolbyReference(vec3 source,vec3 blur){
      vec3 clean=reduceSdrNoise(source,blur,.32);
      float y=max(luma2020(clean),1e-5);
      float mappedY=blackAnchoredLift(y,.13);
      mappedY=pivotContrast(mappedY,.155,.40);
      mappedY=highlightShoulder(mappedY,.70,1.20);
      vec3 color=clean*(mappedY/y);
      color=vec3(mappedY)+(color-vec3(mappedY))*1.02;
      float coolHighlight=smoothstep(.62,.95,mappedY)*.006;
      color+=vec3(.10,.26,1.0)*coolHighlight;
      return restoreOpticalDetail(color,clean,blur,.16);
    }

    void main(){
      vec2 uv=coverUv(vUv);
      vec2 texel=1.0/uTextureSize;
      vec3 source=sampleLinear(uv);
      vec3 blur=(
        sampleLinear(uv+vec2(texel.x,0.0))+
        sampleLinear(uv-vec2(texel.x,0.0))+
        sampleLinear(uv+vec2(0.0,texel.y))+
        sampleLinear(uv-vec2(0.0,texel.y))
      )*.25;

      bool visionStudy=uPreset>.5;
      vec3 reference=visionStudy?applyDolbyReference(source,blur):applyHdr10Reference(source,blur);
      vec3 camx=applyCamxProfile(source,blur,visionStudy);
      vec3 outputColor=vUv.x>=uSplit?camx:reference;

      float divider=1.0-smoothstep(0.0,1.65/uResolution.x,abs(vUv.x-uSplit));
      outputColor=mix(outputColor,vec3(.88,.84,1.0),divider*.72);
      gl_FragColor=vec4(toDisplay(outputColor),1.0);
    }
  `;

  function compile(gl,type,source){
    const shader=gl.createShader(type);
    gl.shaderSource(shader,source);
    gl.compileShader(shader);
    if(!gl.getShaderParameter(shader,gl.COMPILE_STATUS)){
      const message=gl.getShaderInfoLog(shader)||"Shader compilation failed";
      gl.deleteShader(shader);
      throw new Error(message);
    }
    return shader;
  }

  function unavailable(card,message){
    card.classList.add("is-unavailable");
    const viewport=card.querySelector(".range-comparison__viewport");
    if(viewport?.querySelector(".range-comparison__fallback"))return;
    const fallback=document.createElement("p");
    fallback.className="range-comparison__fallback";
    fallback.textContent=message;
    viewport?.append(fallback);
  }

  function initComparison(card){
    const canvas=card.querySelector("[data-range-canvas]");
    const slider=card.querySelector("[data-range-slider]");
    const gl=canvas?.getContext("webgl",{
      alpha:false,
      antialias:false,
      depth:false,
      stencil:false,
      premultipliedAlpha:false,
      powerPreference:"high-performance"
    });
    if(!gl){
      unavailable(card,"REAL MASTER AVAILABLE / GPU PROFILE PREVIEW REQUIRES WEBGL");
      return;
    }

    let program;
    try{
      const vertex=compile(gl,gl.VERTEX_SHADER,vertexSource);
      const fragment=compile(gl,gl.FRAGMENT_SHADER,fragmentSource);
      program=gl.createProgram();
      gl.attachShader(program,vertex);
      gl.attachShader(program,fragment);
      gl.linkProgram(program);
      gl.deleteShader(vertex);
      gl.deleteShader(fragment);
      if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw new Error(gl.getProgramInfoLog(program)||"Program link failed");
    }catch(error){
      console.error("CamX comparison renderer failed",error);
      unavailable(card,"REAL MASTER AVAILABLE / GPU PROFILE PREVIEW UNAVAILABLE");
      return;
    }

    const locations={
      position:gl.getAttribLocation(program,"aPosition"),
      source:gl.getUniformLocation(program,"uSource"),
      resolution:gl.getUniformLocation(program,"uResolution"),
      textureSize:gl.getUniformLocation(program,"uTextureSize"),
      split:gl.getUniformLocation(program,"uSplit"),
      preset:gl.getUniformLocation(program,"uPreset")
    };
    const buffer=gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER,buffer);
    gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,-1,1,-1,1,1,-1,1,1]),gl.STATIC_DRAW);
    gl.useProgram(program);
    gl.enableVertexAttribArray(locations.position);
    gl.vertexAttribPointer(locations.position,2,gl.FLOAT,false,0,0);

    const texture=gl.createTexture();
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D,texture);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
    gl.uniform1i(locations.source,0);

    let sourceWidth=1920;
    let sourceHeight=1080;
    let ready=false;
    let visible=true;
    const presetValue=card.dataset.preset==="vision"?1:0;

    const draw=()=>{
      if(!ready||!canvas.width||!canvas.height)return;
      gl.viewport(0,0,canvas.width,canvas.height);
      gl.useProgram(program);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D,texture);
      gl.uniform2f(locations.resolution,canvas.width,canvas.height);
      gl.uniform2f(locations.textureSize,sourceWidth,sourceHeight);
      gl.uniform1f(locations.split,Number(slider.value)/100);
      gl.uniform1f(locations.preset,presetValue);
      gl.drawArrays(gl.TRIANGLES,0,6);
    };

    const resize=()=>{
      const box=canvas.getBoundingClientRect();
      const maxDpr=Math.max(1,Math.min(devicePixelRatio||1,2,2400/Math.max(box.width,1)));
      const width=Math.max(1,Math.round(box.width*maxDpr));
      const height=Math.max(1,Math.round(box.height*maxDpr));
      if(canvas.width!==width||canvas.height!==height){
        canvas.width=width;
        canvas.height=height;
      }
      draw();
    };

    const updateSplit=()=>{
      const value=Number(slider.value);
      const reference=presetValue?"Dolby Vision reference":"HDR10+ reference";
      const camx=presetValue?"NIVUKX VISION":"XDR10+";
      card.style.setProperty("--split",`${value}%`);
      slider.setAttribute("aria-valuetext",`${value}% ${reference}, ${100-value}% ${camx}`);
      draw();
    };

    const image=new Image();
    image.decoding="async";
    image.addEventListener("load",()=>{
      sourceWidth=image.naturalWidth;
      sourceHeight=image.naturalHeight;
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D,texture);
      gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL,true);
      gl.texImage2D(gl.TEXTURE_2D,0,gl.RGB,gl.RGB,gl.UNSIGNED_BYTE,image);
      ready=true;
      card.classList.add("is-ready");
      resize();
    },{once:true});
    image.addEventListener("error",()=>unavailable(card,"SOURCE MASTER COULD NOT BE LOADED"),{once:true});
    image.src=SOURCE_IMAGE;

    slider?.addEventListener("input",updateSplit,{passive:true});
    slider?.addEventListener("change",updateSplit,{passive:true});
    const resizeObserver="ResizeObserver" in window?new ResizeObserver(resize):null;
    resizeObserver?.observe(canvas);
    addEventListener("resize",resize,{passive:true});
    const visibilityObserver="IntersectionObserver" in window?new IntersectionObserver(entries=>{
      visible=entries[0]?.isIntersecting??true;
      if(visible)draw();
    },{rootMargin:"240px 0px",threshold:0}):null;
    visibilityObserver?.observe(card);
    canvas.addEventListener("webglcontextlost",event=>{
      event.preventDefault();
      visible=false;
      unavailable(card,"GPU CONTEXT PAUSED / REAL MASTER REMAINS AVAILABLE");
    },{once:true});

    updateSplit();
    resize();
  }

  document.querySelectorAll("[data-range-comparison]").forEach(initComparison);
})();
