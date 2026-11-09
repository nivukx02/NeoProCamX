import React, { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { Canvas, useFrame, useThree } from "@react-three/fiber";
import { ContactShadows, Environment, Lightformer, OrbitControls, PerspectiveCamera, RenderTexture, RoundedBox } from "@react-three/drei";
import * as THREE from "three";
import Lenis from "lenis";
import { animate as motionAnimate } from "motion";

(async()=>{
const h=React.createElement;
const reduced=matchMedia("(prefers-reduced-motion: reduce)").matches;
const gsap=window.gsap,ScrollTrigger=window.ScrollTrigger;
const webglReady=(()=>{try{const canvas=document.createElement("canvas");return Boolean(canvas.getContext("webgl2",{failIfMajorPerformanceCaveat:true})||canvas.getContext("webgl",{failIfMajorPerformanceCaveat:true}))}catch{return false}})();
const STAGES="#camx-device,#operator-3d-stage,#format-stage,#xdr-3d-stage";

function showStageError(node,message){node.classList.add("is-unavailable");const status=node.querySelector(".stage-loader")||document.createElement("p");status.className="stage-loader stage-loader--error";status.setAttribute("role","alert");status.textContent=message;node.replaceChildren(status)}
function configureRenderer(exposure=1.08){return({gl})=>{gl.toneMapping=THREE.ACESFilmicToneMapping;gl.toneMappingExposure=exposure;gl.outputColorSpace=THREE.SRGBColorSpace}}
function useStageActivity(host){
  const [active,setActive]=useState(true),intersecting=useRef(true);
  useEffect(()=>{if(!host)return;const sync=()=>setActive(intersecting.current&&!document.hidden);const observer="IntersectionObserver" in window?new IntersectionObserver(entries=>{intersecting.current=entries[0]?.isIntersecting??true;sync()},{rootMargin:"120px 0px",threshold:0}):null;observer?.observe(host);document.addEventListener("visibilitychange",sync);return()=>{observer?.disconnect();document.removeEventListener("visibilitychange",sync)}},[host]);
  return active;
}
function AdaptiveCamera({desktop=[0,.3,9],mobile=[0,.2,11.5],desktopFov=40,mobileFov=45}){
  const {camera,size}=useThree();
  useLayoutEffect(()=>{const compact=size.width<640,target=compact?mobile:desktop;camera.position.set(...target);camera.fov=compact?mobileFov:desktopFov;camera.updateProjectionMatrix()},[camera,size.width,desktop,mobile,desktopFov,mobileFov]);
  return null;
}
function roundRect(ctx,x,y,w,height,r,fill,stroke){ctx.beginPath();ctx.roundRect(x,y,w,height,r);if(fill){ctx.fillStyle=fill;ctx.fill()}if(stroke){ctx.strokeStyle=stroke;ctx.lineWidth=2;ctx.stroke()}}

function buildCamXTexture(){
  const canvas=document.createElement("canvas");canvas.width=720;canvas.height=1560;const ctx=canvas.getContext("2d");
  ctx.fillStyle="#020203";ctx.fillRect(0,0,720,1560);
  const scene=ctx.createLinearGradient(0,180,720,1100);scene.addColorStop(0,"#0b0a0e");scene.addColorStop(.5,"#322b3b");scene.addColorStop(1,"#09080b");ctx.fillStyle=scene;ctx.fillRect(0,180,720,900);
  const glow=ctx.createRadialGradient(545,500,10,545,500,370);glow.addColorStop(0,"#9c82ff88");glow.addColorStop(.5,"#4b347744");glow.addColorStop(1,"#09080b00");ctx.fillStyle=glow;ctx.fillRect(0,180,720,900);
  ctx.fillStyle="#0b0a0d";ctx.beginPath();ctx.moveTo(0,760);ctx.lineTo(240,485);ctx.lineTo(385,760);ctx.lineTo(555,410);ctx.lineTo(720,645);ctx.lineTo(720,1080);ctx.lineTo(0,1080);ctx.fill();
  ctx.strokeStyle="#ffffff32";ctx.lineWidth=1;ctx.strokeRect(52,240,616,770);ctx.beginPath();ctx.moveTo(258,240);ctx.lineTo(258,1010);ctx.moveTo(462,240);ctx.lineTo(462,1010);ctx.moveTo(52,625);ctx.lineTo(668,625);ctx.stroke();
  roundRect(ctx,65,55,590,92,36,"#17161bdd","#5f5a66");ctx.textAlign="center";ctx.fillStyle="#f1eee8";ctx.font="26px Arial";ctx.fillText("8192×6144  |  4096×3072  |  4:3  |  26mm",360,90);
  ["● PROV","● P010","● LAB","● NATIVE"].forEach((label,index)=>{const x=154+index*136;roundRect(ctx,x-58,105,116,30,15,"#0d0c10","#7657ff");ctx.fillStyle="#b7a8ff";ctx.font="15px monospace";ctx.fillText(label,x,126)});
  ctx.fillStyle="#f2eee7";ctx.font="14px monospace";ctx.textAlign="left";ctx.fillText("STBY     A001 C001     00:00:00:00",26,210);
  ctx.strokeStyle="#b7a8ff";ctx.strokeRect(330,555,70,44);ctx.fillStyle="#b7a8ff";ctx.font="12px monospace";ctx.fillText("AF / 01",335,620);
  const labels=["EV","ISO","S","WB","MF","METER"],values=["0","AUTO","180°","4300K","1.5m","MATRIX"];
  ctx.fillStyle="#070608e8";ctx.fillRect(0,1080,720,190);labels.forEach((label,index)=>{const x=60+index*120;ctx.strokeStyle="#77717d";ctx.strokeRect(x-25,1100,50,50);ctx.textAlign="center";ctx.fillStyle="#f3eee7";ctx.font="17px Arial";ctx.fillText(label,x,1132);ctx.fillStyle="#736e78";ctx.font="12px monospace";ctx.fillText(label,x,1175);ctx.fillStyle="#f3eee7";ctx.font="16px Arial";ctx.fillText(values[index],x,1210)});
  roundRect(ctx,265,1287,190,64,32,"#4b4650","#7d7783");ctx.fillStyle="#f4f0e9";ctx.font="bold 29px Arial";ctx.fillText("XDR",360,1329);
  roundRect(ctx,125,1380,470,135,68,"#312e35","#68636d");[205,515].forEach(x=>{ctx.beginPath();ctx.arc(x,1448,42,0,Math.PI*2);ctx.fillStyle="#5b5660";ctx.fill();ctx.strokeStyle="#8c8691";ctx.stroke()});ctx.beginPath();ctx.arc(360,1448,58,0,Math.PI*2);ctx.strokeStyle="#eee8dc";ctx.lineWidth=6;ctx.stroke();ctx.beginPath();ctx.arc(360,1448,45,0,Math.PI*2);ctx.fillStyle="#7657ff";ctx.fill();
  const texture=new THREE.CanvasTexture(canvas);texture.colorSpace=THREE.SRGBColorSpace;texture.anisotropy=8;return texture;
}

function buildCamXOverlayTexture(){
  const canvas=document.createElement("canvas");canvas.width=720;canvas.height=1560;const ctx=canvas.getContext("2d");ctx.clearRect(0,0,720,1560);
  ctx.strokeStyle="#ffffff42";ctx.lineWidth=1;ctx.strokeRect(52,240,616,770);ctx.beginPath();ctx.moveTo(258,240);ctx.lineTo(258,1010);ctx.moveTo(462,240);ctx.lineTo(462,1010);ctx.moveTo(52,625);ctx.lineTo(668,625);ctx.stroke();
  roundRect(ctx,65,55,590,92,36,"#111014e8","#6f6877");ctx.textAlign="center";ctx.fillStyle="#f1eee8";ctx.font="26px Arial";ctx.fillText("8192×6144  |  4096×3072  |  4:3  |  26mm",360,90);
  ["● PROV","● P010","● LAB","● NATIVE"].forEach((label,index)=>{const x=154+index*136;roundRect(ctx,x-58,105,116,30,15,"#0d0c10e8","#7657ff");ctx.fillStyle="#c6baff";ctx.font="15px monospace";ctx.fillText(label,x,126)});
  ctx.fillStyle="#f2eee7";ctx.font="14px monospace";ctx.textAlign="left";ctx.fillText("STBY     A001 C001     00:00:00:00",26,210);
  ctx.strokeStyle="#c6baff";ctx.lineWidth=2;ctx.strokeRect(330,555,70,44);ctx.fillStyle="#c6baff";ctx.font="12px monospace";ctx.fillText("AF / 01",335,620);
  const labels=["EV","ISO","S","WB","MF","METER"],values=["0","100","180°","4300K","1.5m","MATRIX"];
  ctx.fillStyle="#070608ed";ctx.fillRect(0,1080,720,190);labels.forEach((label,index)=>{const x=60+index*120;ctx.strokeStyle="#8a8490";ctx.strokeRect(x-25,1100,50,50);ctx.textAlign="center";ctx.fillStyle="#f3eee7";ctx.font="17px Arial";ctx.fillText(label,x,1132);ctx.fillStyle="#857e8a";ctx.font="12px monospace";ctx.fillText(label,x,1175);ctx.fillStyle="#f3eee7";ctx.font="16px Arial";ctx.fillText(values[index],x,1210)});
  roundRect(ctx,265,1287,190,64,32,"#3c3842ee","#8f8796");ctx.fillStyle="#f4f0e9";ctx.font="bold 29px Arial";ctx.fillText("XDR",360,1329);
  roundRect(ctx,125,1380,470,135,68,"#27242bea","#77717d");[205,515].forEach(x=>{ctx.beginPath();ctx.arc(x,1448,42,0,Math.PI*2);ctx.fillStyle="#514c56";ctx.fill();ctx.strokeStyle="#9c95a2";ctx.stroke()});ctx.beginPath();ctx.arc(360,1448,58,0,Math.PI*2);ctx.strokeStyle="#eee8dc";ctx.lineWidth=6;ctx.stroke();ctx.beginPath();ctx.arc(360,1448,45,0,Math.PI*2);ctx.fillStyle="#7657ff";ctx.fill();
  const texture=new THREE.CanvasTexture(canvas);texture.colorSpace=THREE.SRGBColorSpace;texture.anisotropy=8;return texture;
}

function StudioEnvironment({accent="#7657ff",intensity=1}){
  return h(React.Fragment,null,
    h("ambientLight",{intensity:.18*intensity,color:"#bcb4c8"}),
    h("spotLight",{position:[-5,7,7],intensity:42*intensity,angle:.34,penumbra:.9,color:"#f5eee2",castShadow:true}),
    h("pointLight",{position:[5,-1,5],intensity:28*intensity,color:accent}),
    h("pointLight",{position:[-4,-3,3],intensity:12*intensity,color:"#a79ac8"}),
    h(Environment,{resolution:128},
      h(Lightformer,{form:"rect",intensity:4,color:"#fff8ee",position:[-4,5,3],scale:[4,4,1]}),
      h(Lightformer,{form:"ring",intensity:5,color:accent,position:[4,1,-2],scale:3}),
      h(Lightformer,{form:"rect",intensity:2,color:"#6b6277",position:[0,-4,2],scale:[6,2,1]})
    )
  );
}

function LensModule({position=[0,0,0],scale=1,accent="#7657ff"}){
  return h("group",{position,scale},
    h("mesh",{rotation:[Math.PI/2,0,0],castShadow:true},h("cylinderGeometry",{args:[.72,.76,.22,72]}),h("meshStandardMaterial",{color:"#17151a",metalness:.95,roughness:.15})),
    h("mesh",{position:[0,0,.13]},h("torusGeometry",{args:[.55,.08,24,96]}),h("meshStandardMaterial",{color:"#55505b",metalness:1,roughness:.1})),
    h("mesh",{position:[0,0,.15],rotation:[Math.PI/2,0,0]},h("cylinderGeometry",{args:[.48,.52,.06,72]}),h("meshPhysicalMaterial",{color:"#1a1430",metalness:.16,roughness:.05,transmission:.36,thickness:.85,clearcoat:1,iridescence:1,iridescenceIOR:1.8})),
    h("mesh",{position:[-.14,.15,.19]},h("circleGeometry",{args:[.13,48]}),h("meshBasicMaterial",{color:accent,transparent:true,opacity:.38}))
  );
}

function CineLens({position=[0,0,0],rotation=[0,0,0],scale=1,accent="#7657ff",compact=false}){
  const teeth=compact?16:28;
  return h("group",{position,rotation,scale},
    h("mesh",{rotation:[Math.PI/2,0,0],castShadow:true},h("cylinderGeometry",{args:[.82,.86,1.55,96]}),h("meshPhysicalMaterial",{color:"#111114",metalness:.92,roughness:.2,clearcoat:.65,clearcoatRoughness:.16})),
    [-.56,-.28,.28,.57].map((z,index)=>h("mesh",{key:`ring-${index}`,position:[0,0,z]},h("torusGeometry",{args:[.82,index===1||index===2?.085:.055,20,96]}),h("meshStandardMaterial",{color:index===1||index===2?"#48434b":"#222026",metalness:1,roughness:.13}))),
    Array.from({length:teeth},(_,index)=>{const angle=index/teeth*Math.PI*2;return h("mesh",{key:`tooth-${index}`,position:[Math.cos(angle)*.91,Math.sin(angle)*.91,-.28],rotation:[0,0,angle]},h("boxGeometry",{args:[.08,.16,.12]}),h("meshStandardMaterial",{color:"#242128",metalness:.96,roughness:.16}))}),
    h("mesh",{position:[0,0,.81],rotation:[Math.PI/2,0,0]},h("cylinderGeometry",{args:[.69,.75,.12,96]}),h("meshPhysicalMaterial",{color:"#151027",metalness:.08,roughness:.025,transmission:.42,thickness:1.2,ior:1.52,clearcoat:1,iridescence:1,iridescenceIOR:1.7})),
    h("mesh",{position:[-.2,.2,.89]},h("circleGeometry",{args:[.19,64]}),h("meshBasicMaterial",{color:accent,transparent:true,opacity:.3,toneMapped:false})),
    h("mesh",{position:[0,0,.9]},h("torusGeometry",{args:[.65,.045,20,96]}),h("meshStandardMaterial",{color:"#77717a",metalness:1,roughness:.08}))
  );
}

function ScreenStillLife(){
  return h("group",{position:[0,-.2,0],scale:.92},
    h("mesh",{position:[0,0,-2.8]},h("planeGeometry",{args:[10,7]}),h("meshStandardMaterial",{color:"#100e13",roughness:.82})),
    h("mesh",{rotation:[-Math.PI/2,0,0],position:[0,-1.75,-.3]},h("planeGeometry",{args:[10,8]}),h("meshStandardMaterial",{color:"#09090a",metalness:.18,roughness:.5})),
    h("mesh",{position:[-2.2,.4,-1.4]},h("boxGeometry",{args:[1.15,3.25,.22]}),h("meshBasicMaterial",{color:"#e8dcc8",toneMapped:false})),
    h("rectAreaLight",{position:[-2.15,.4,-1.15],width:1,height:3,intensity:8,color:"#f4dfc3"}),
    h("group",{position:[.15,-.36,.2]},h(RoundedBox,{args:[2.55,.7,1.5],radius:.18,smoothness:6,castShadow:true},h("meshStandardMaterial",{color:"#242127",metalness:.72,roughness:.24})),h(CineLens,{position:[0,.67,.35],rotation:[0,0,0],scale:.78,compact:true})),
    h("mesh",{position:[2,-.6,.15],rotation:[.15,0,.25],castShadow:true},h("cylinderGeometry",{args:[.58,.58,1.35,3]}),h("meshPhysicalMaterial",{color:"#7860ac",transmission:.65,thickness:1.1,ior:1.48,roughness:.05,transparent:true,opacity:.78,clearcoat:1})),
    h("pointLight",{position:[2.2,.7,2],intensity:18,color:"#7657ff"})
  );
}

function LiveViewfinder(){
  return h(RenderTexture,{attach:"map",anisotropy:8,frames:Infinity},
    h("color",{attach:"background",args:["#08070a"]}),
    h(PerspectiveCamera,{makeDefault:true,position:[0,.35,6.5],fov:42}),
    h("ambientLight",{intensity:.35,color:"#bdb4c4"}),
    h("spotLight",{position:[-4,5,5],intensity:26,angle:.42,penumbra:.9,color:"#f6ead9"}),
    h("pointLight",{position:[4,-1,4],intensity:18,color:"#7657ff"}),
    h(ScreenStillLife)
  );
}

function PhoneModel({screen=true}){
  const texture=useMemo(()=>buildCamXTexture(),[]),overlay=useMemo(()=>buildCamXOverlayTexture(),[]);
  return h("group",null,
    h(RoundedBox,{args:[3.2,6.72,.42],radius:.38,smoothness:12,castShadow:true},h("meshPhysicalMaterial",{color:"#252329",metalness:.96,roughness:.14,clearcoat:.82,clearcoatRoughness:.1})),
    h(RoundedBox,{args:[3.08,6.58,.08],radius:.32,smoothness:10,position:[0,0,.24]},h("meshPhysicalMaterial",{color:"#020203",metalness:.12,roughness:.025,clearcoat:1})),
    screen&&h("mesh",{position:[0,0,.286]},h("planeGeometry",{args:[2.94,6.42]}),h("meshBasicMaterial",{toneMapped:false},h(LiveViewfinder))),
    screen&&h("mesh",{position:[0,0,.291]},h("planeGeometry",{args:[2.94,6.42]}),h("meshBasicMaterial",{map:overlay,transparent:true,toneMapped:false,depthWrite:false})),
    !screen&&h("mesh",{position:[0,0,.282]},h("planeGeometry",{args:[2.94,6.42]}),h("meshBasicMaterial",{map:texture,toneMapped:false})),
    h(RoundedBox,{args:[.7,.15,.045],radius:.07,smoothness:4,position:[0,3.02,.31]},h("meshBasicMaterial",{color:"#010102"})),
    h("mesh",{position:[1.63,.92,0]},h("boxGeometry",{args:[.055,1.08,.13]}),h("meshStandardMaterial",{color:"#625d68",metalness:1,roughness:.12})),
    h("mesh",{position:[-1.63,1.35,0]},h("boxGeometry",{args:[.055,.66,.13]}),h("meshStandardMaterial",{color:"#625d68",metalness:1,roughness:.12})),
    [-2.45,2.45].map((y,index)=>h("mesh",{key:`antenna-${index}`,position:[1.605,y,0]},h("boxGeometry",{args:[.018,.28,.43]}),h("meshStandardMaterial",{color:"#88818b",metalness:.75,roughness:.23}))),
    h("group",{position:[-.83,1.92,-.29]},h(RoundedBox,{args:[1.22,1.75,.18],radius:.25,smoothness:8},h("meshPhysicalMaterial",{color:"#17151a",metalness:.96,roughness:.13,clearcoat:.62})),h(LensModule,{position:[-.27,.38,.11],scale:.37}),h(LensModule,{position:[.29,-.42,.11],scale:.37,accent:"#b7a8ff"}),h("mesh",{position:[.34,.46,.14]},h("circleGeometry",{args:[.15,32]}),h("meshPhysicalMaterial",{color:"#e8ddc8",emissive:"#d8bea2",emissiveIntensity:.45,clearcoat:1})))
  );
}

function CageBar({args,position,rotation=[0,0,0]}){
  return h(RoundedBox,{args,radius:.08,smoothness:4,position,rotation,castShadow:true},h("meshPhysicalMaterial",{color:"#252329",metalness:.96,roughness:.18,clearcoat:.42,clearcoatRoughness:.16}));
}

function CinemaCage(){
  return h("group",null,
    h(CageBar,{args:[.18,7.2,.34],position:[-1.86,0,-.05]}),h(CageBar,{args:[.18,7.2,.34],position:[1.86,0,-.05]}),
    h(CageBar,{args:[3.88,.18,.34],position:[0,3.51,-.05]}),h(CageBar,{args:[3.88,.18,.34],position:[0,-3.51,-.05]}),
    Array.from({length:10},(_,index)=>{const y=-2.8+index*.62;return h("mesh",{key:index,position:[-1.94,y,.11]},h("boxGeometry",{args:[.08,.2,.12]}),h("meshStandardMaterial",{color:"#77717c",metalness:1,roughness:.12}))}),
    h("group",{position:[0,4.18,-.05]},
      h(CageBar,{args:[2.7,.3,.38],position:[0,.38,0]}),h(CageBar,{args:[.26,1.02,.38],position:[-1.2,0,0]}),h(CageBar,{args:[.26,1.02,.38],position:[1.2,0,0]}),
      h(RoundedBox,{args:[1.8,.5,.48],radius:.2,smoothness:6,position:[0,.38,.02]},h("meshStandardMaterial",{color:"#101013",metalness:.72,roughness:.34}))
    ),
    h("group",{position:[2.38,-.25,-.04]},
      h(RoundedBox,{args:[.78,3.15,.72],radius:.24,smoothness:8,castShadow:true},h("meshPhysicalMaterial",{color:"#1b191e",metalness:.88,roughness:.24,clearcoat:.34})),
      h("mesh",{position:[-.42,.7,0]},h("cylinderGeometry",{args:[.08,.08,.34,32]}),h("meshStandardMaterial",{color:"#6f6974",metalness:1,roughness:.12})),
      Array.from({length:5},(_,index)=>h("mesh",{key:index,position:[.4,-.88+index*.44,.08]},h("boxGeometry",{args:[.035,.12,.32]}),h("meshStandardMaterial",{color:"#4c4750",metalness:.94,roughness:.14})))
    ),
    h("group",{position:[0,-3.95,-.18]},
      h(CageBar,{args:[4.1,.32,.65],position:[0,0,0]}),
      [-.58,.58].map((z,index)=>h("mesh",{key:index,position:[0,-.3,z],rotation:[0,Math.PI/2,0]},h("cylinderGeometry",{args:[.09,.09,5.4,32]}),h("meshStandardMaterial",{color:"#4d4850",metalness:1,roughness:.12})))
    ),
    h("group",{position:[-2.45,1.55,-.18]},
      h(RoundedBox,{args:[.72,1.85,.42],radius:.12,smoothness:6},h("meshPhysicalMaterial",{color:"#1a181c",metalness:.9,roughness:.2,clearcoat:.45})),
      h("mesh",{position:[0,0,.24]},h("planeGeometry",{args:[.5,1.4]}),h("meshStandardMaterial",{color:"#302842",emissive:"#251550",emissiveIntensity:.18,metalness:.35,roughness:.22}))
    )
  );
}

function HeroRig(){
  const rig=useRef();
  useFrame((state,delta)=>{if(!rig.current||reduced)return;rig.current.rotation.x=THREE.MathUtils.damp(rig.current.rotation.x,.035+state.pointer.y*.055,4,delta);rig.current.rotation.y=THREE.MathUtils.damp(rig.current.rotation.y,-.22+state.pointer.x*.13,4,delta);rig.current.position.y=THREE.MathUtils.damp(rig.current.position.y,-.12+Math.sin(state.clock.elapsedTime*.4)*.035,4,delta)});
  return h("group",{ref:rig,position:[0,-.08,.15],rotation:[.035,-.22,.018],scale:.66},
    h(CinemaCage),h(PhoneModel),
    h("mesh",{rotation:[-Math.PI/2,0,0],position:[0,-4.45,-.4],receiveShadow:true},h("planeGeometry",{args:[14,14]}),h("shadowMaterial",{transparent:true,opacity:.38}))
  );
}

function DeviceExperience({host}){const active=useStageActivity(host);return h(Canvas,{frameloop:active?"always":"never",camera:{position:[0,.15,9.2],fov:37},dpr:[1,1.5],shadows:true,gl:{antialias:true,alpha:true,powerPreference:"high-performance"},onCreated:configureRenderer(1.08),style:{background:"transparent"}},h(AdaptiveCamera,{desktop:[0,.15,9.2],mobile:[0,.15,11.4],desktopFov:37,mobileFov:43}),h(StudioEnvironment,{intensity:1.05}),h(HeroRig),h(ContactShadows,{position:[0,-3.05,0],opacity:.52,scale:10,blur:2.8,far:5,color:"#050408"}),h(OrbitControls,{enablePan:false,enableZoom:false,minPolarAngle:Math.PI*.38,maxPolarAngle:Math.PI*.62,rotateSpeed:.34}))}

const captureProfiles={
  photo:{accent:"#7657ff",key:"#f1dfc4",shadow:"#17131d",sensor:"#8d74ff",planes:1,motion:false,neutral:false},
  "pro-photo":{accent:"#9d82ff",key:"#ead6bb",shadow:"#100e14",sensor:"#baa8ff",planes:3,motion:false,neutral:false},
  "raw-photo":{accent:"#b8b0c0",key:"#e4ddd4",shadow:"#27232b",sensor:"#bcb4c2",planes:5,motion:false,neutral:true},
  "xdr-photo":{accent:"#7e5cff",key:"#fff2d7",shadow:"#09070d",sensor:"#a68fff",planes:13,motion:false,neutral:false},
  video:{accent:"#7254ee",key:"#e7d7c1",shadow:"#121019",sensor:"#8368f5",planes:2,motion:true,neutral:false},
  "pro-video":{accent:"#a589ff",key:"#f2ddc0",shadow:"#0c0a0e",sensor:"#b29dff",planes:4,motion:true,neutral:false},
  xdr10:{accent:"#8867ff",key:"#fff2dc",shadow:"#07060a",sensor:"#c1b2ff",planes:10,motion:true,neutral:false},
  nivukx:{accent:"#ba63ff",key:"#e8deff",shadow:"#0a0710",sensor:"#d48cff",planes:7,motion:true,neutral:false},
  raw:{accent:"#aaa3b0",key:"#ded8d0",shadow:"#211e24",sensor:"#c7c0ca",planes:6,motion:true,neutral:true},
  log:{accent:"#776d83",key:"#d7d0c5",shadow:"#242127",sensor:"#948a9d",planes:8,motion:true,neutral:true}
};

function PracticalLight({profile}){
  return h("group",{position:[-1.55,.05,.55]},
    h("mesh",{position:[0,-1.13,0],castShadow:true},h("cylinderGeometry",{args:[.55,.7,.16,64]}),h("meshStandardMaterial",{color:"#19171b",metalness:.92,roughness:.18})),
    h("mesh",{position:[0,-.55,0],castShadow:true},h("cylinderGeometry",{args:[.18,.28,1.05,48]}),h("meshStandardMaterial",{color:"#252129",metalness:.88,roughness:.2})),
    h("mesh",{position:[0,.12,0],castShadow:true},h("sphereGeometry",{args:[.46,64,64]}),h("meshPhysicalMaterial",{color:profile.key,transmission:.48,thickness:.7,roughness:.08,transparent:true,opacity:.88,emissive:profile.key,emissiveIntensity:1.3,clearcoat:1})),
    h("pointLight",{position:[0,.15,.4],intensity:18,color:profile.key,distance:5,decay:2})
  );
}

function CinemaStillLife({profile}){
  const focus=useRef();
  useFrame(({clock})=>{if(focus.current&&!reduced)focus.current.rotation.z=Math.sin(clock.elapsedTime*.28)*.055});
  return h("group",{position:[.05,-.2,-.12],rotation:[0,-.12,0]},
    h("group",{position:[.15,-.78,.15]},
      h(RoundedBox,{args:[2.65,.74,1.65],radius:.2,smoothness:8,castShadow:true},h("meshPhysicalMaterial",{color:"#242127",metalness:.78,roughness:.24,clearcoat:.45,clearcoatRoughness:.19})),
      h("mesh",{position:[0,-.43,0],castShadow:true},h("cylinderGeometry",{args:[.52,.68,.35,64]}),h("meshStandardMaterial",{color:"#111013",metalness:.9,roughness:.2}))
    ),
    h("group",{ref:focus,position:[.12,.42,.52]},h(CineLens,{scale:.78,accent:profile.accent})),
    h("group",{position:[1.72,-.58,.34],rotation:[.14,.08,.28]},
      h("mesh",{castShadow:true},h("cylinderGeometry",{args:[.61,.61,1.48,3]}),h("meshPhysicalMaterial",{color:profile.neutral?"#8b8790":profile.accent,transmission:.68,thickness:1.3,ior:1.5,roughness:.035,transparent:true,opacity:.74,clearcoat:1,iridescence:.35})),
      h("mesh",{position:[0,-.82,0],rotation:[0,0,-.28]},h("cylinderGeometry",{args:[.43,.53,.12,64]}),h("meshStandardMaterial",{color:"#171519",metalness:.88,roughness:.18}))
    ),
    h(PracticalLight,{profile}),
    h("group",{position:[-2.05,-1.3,.12],rotation:[Math.PI/2,0,0]},
      h("mesh",{castShadow:true},h("cylinderGeometry",{args:[.48,.48,.22,64]}),h("meshStandardMaterial",{color:"#252229",metalness:.96,roughness:.16})),
      h("mesh",{position:[0,.13,0]},h("torusGeometry",{args:[.29,.065,20,72]}),h("meshStandardMaterial",{color:"#77717b",metalness:1,roughness:.12}))
    )
  );
}

function PortalLight({profile}){
  return h("group",{position:[-2.75,.25,-1.42],rotation:[0,.08,0]},
    h("mesh",{position:[0,0,-.04]},h("planeGeometry",{args:[1.52,3.8]}),h("meshBasicMaterial",{color:profile.key,toneMapped:false})),
    h("mesh",{position:[0,2.02,0]},h("boxGeometry",{args:[1.85,.18,.2]}),h("meshStandardMaterial",{color:"#19171c",metalness:.84,roughness:.22})),
    h("mesh",{position:[0,-2.02,0]},h("boxGeometry",{args:[1.85,.18,.2]}),h("meshStandardMaterial",{color:"#19171c",metalness:.84,roughness:.22})),
    h("mesh",{position:[-.84,0,0]},h("boxGeometry",{args:[.18,4.2,.2]}),h("meshStandardMaterial",{color:"#19171c",metalness:.84,roughness:.22})),
    h("mesh",{position:[.84,0,0]},h("boxGeometry",{args:[.18,4.2,.2]}),h("meshStandardMaterial",{color:"#19171c",metalness:.84,roughness:.22})),
    h("rectAreaLight",{position:[0,0,.1],rotation:[0,0,0],width:1.5,height:3.75,intensity:8,color:profile.key})
  );
}

function SensorGate({profile}){
  const material={color:"#39343e",metalness:.94,roughness:.15};
  return h("group",{position:[2.65,.05,.72],rotation:[0,-.32,0]},
    h("mesh",{position:[0,1.25,0]},h("boxGeometry",{args:[2.05,.12,.18]}),h("meshStandardMaterial",material)),
    h("mesh",{position:[0,-1.25,0]},h("boxGeometry",{args:[2.05,.12,.18]}),h("meshStandardMaterial",material)),
    h("mesh",{position:[-.98,0,0]},h("boxGeometry",{args:[.12,2.6,.18]}),h("meshStandardMaterial",material)),
    h("mesh",{position:[.98,0,0]},h("boxGeometry",{args:[.12,2.6,.18]}),h("meshStandardMaterial",material)),
    h("mesh",{position:[0,0,-.02]},h("planeGeometry",{args:[1.82,2.32]}),h("meshPhysicalMaterial",{color:profile.sensor,emissive:profile.accent,emissiveIntensity:.18,metalness:.34,roughness:.12,transparent:true,opacity:.22,transmission:.22,side:THREE.DoubleSide})),
    h("mesh",{position:[0,0,.12]},h("torusGeometry",{args:[.56,.055,20,80]}),h("meshStandardMaterial",{color:profile.accent,emissive:profile.accent,emissiveIntensity:.5,metalness:.7,roughness:.16})),
    Array.from({length:12},(_,index)=>{const x=-.78+(index%4)*.52,y=.92-Math.floor(index/4)*.92;return h("mesh",{key:index,position:[x,y,.035]},h("boxGeometry",{args:[.035,.035,.025]}),h("meshBasicMaterial",{color:index%3===0?profile.key:profile.accent,toneMapped:false}))})
  );
}

function ExposureVolume({profile}){
  const count=Math.min(profile.planes,13);
  return h("group",null,Array.from({length:count},(_,index)=>{const t=count===1?.5:index/(count-1);return h("mesh",{key:index,position:[-1.45+t*3.25,.1,-.75+t*.85],rotation:[0,-.18+t*.24,0]},h("planeGeometry",{args:[2.55,3.55]}),h("meshPhysicalMaterial",{color:t<.35?profile.shadow:t>.72?profile.key:profile.accent,transparent:true,opacity:.035+t*.025,transmission:.5,roughness:.08,side:THREE.DoubleSide,depthWrite:false}))}));
}

function FrameTransport({profile,frameRefs}){
  if(!profile.motion)return null;
  return h("group",null,
    h(RoundedBox,{args:[6.8,.62,.18],radius:.08,smoothness:4,position:[0,-1.72,-.18]},h("meshPhysicalMaterial",{color:"#151319",metalness:.82,roughness:.25,clearcoat:.28})),
    Array.from({length:7},(_,index)=>h("mesh",{key:`chip-${index}`,position:[-2.75+index*.92,-1.72,-.04]},h("boxGeometry",{args:[.52,.32,.08]}),h("meshPhysicalMaterial",{color:"#24202a",emissive:index%2?profile.accent:"#000000",emissiveIntensity:index%2?.08:0,metalness:.78,roughness:.18,clearcoat:.45}))),
    h(CircuitTrace,{points:[[-3,-1.72,-.02],[-1.5,-1.72,-.01],[0,-1.72,0],[1.5,-1.72,.02],[3,-1.72,.04]],color:profile.accent}),
    Array.from({length:12},(_,index)=>h("mesh",{key:index,ref:node=>{frameRefs.current[index]=node}},h("sphereGeometry",{args:[.06,20,20]}),h("meshBasicMaterial",{color:index%4===0?profile.key:profile.accent,toneMapped:false})))
  );
}

function CaptureVolume({mode="photo",operator=false}){
  const profile=captureProfiles[mode]||captureProfiles.photo;
  const volume=useRef(),frameRefs=useRef([]),focus=useRef();
  useFrame(({clock},delta)=>{
    const time=clock.elapsedTime;
    if(volume.current&&!reduced){volume.current.rotation.y=THREE.MathUtils.damp(volume.current.rotation.y,Math.sin(time*.22)*.035,3,delta);volume.current.position.y=Math.sin(time*.5)*.025}
    if(focus.current&&!reduced)focus.current.position.z=Math.sin(time*.7)*.35+.2;
    frameRefs.current.forEach((node,index)=>{if(!node)return;const t=((index+time*2.2)%12)/11;node.position.set(-3.1+t*6.2,-1.72+Math.sin(t*Math.PI)*.06,.06+t*.08);node.scale.setScalar(.38+Math.sin(t*Math.PI)*.9)});
  });
  return h("group",{ref:volume,scale:operator?.94:1},
    h("mesh",{position:[0,0,-2.6]},h("planeGeometry",{args:[11,6.4]}),h("meshStandardMaterial",{color:profile.shadow,metalness:.08,roughness:.66})),
    h("mesh",{rotation:[-Math.PI/2,0,0],position:[0,-2.2,-.1],receiveShadow:true},h("planeGeometry",{args:[12,9]}),h("meshStandardMaterial",{color:"#09080b",metalness:.35,roughness:.42})),
    h(PortalLight,{profile}),h(CinemaStillLife,{profile}),h(SensorGate,{profile}),h(ExposureVolume,{profile}),h(FrameTransport,{profile,frameRefs}),
    h("group",{ref:focus,position:[.15,.12,.2]},h("mesh",null,h("planeGeometry",{args:[3.1,3.7]}),h("meshBasicMaterial",{color:profile.accent,wireframe:true,transparent:true,opacity:.1,side:THREE.DoubleSide}))),
    h("mesh",{position:[-3.65,-1.25,-.9]},h("sphereGeometry",{args:[.24,48,48]}),h("meshStandardMaterial",{color:profile.key,emissive:profile.key,emissiveIntensity:2.4})),
    h("pointLight",{position:[-2.4,2.2,2.5],intensity:profile.neutral?18:28,color:profile.key}),
    h("pointLight",{position:[2.8,-.8,2.6],intensity:24,color:profile.accent})
  );
}

function OperatorExperience({host}){
  const [signal,setSignal]=useState({mode:"pro-video",version:0});
  const active=useStageActivity(host);
  useEffect(()=>{const listener=event=>{const map={EV:"xdr-photo",ISO:"raw-photo",S:"pro-video",WB:"pro-photo",MF:"photo",METER:"nivukx",REC:"video"};setSignal(current=>({mode:map[event.detail.control]||current.mode,version:current.version+1}))};addEventListener("camx-operator-control",listener);return()=>removeEventListener("camx-operator-control",listener)},[]);
  const profile=captureProfiles[signal.mode]||captureProfiles["pro-video"];
  return h(Canvas,{frameloop:active?"always":"never",camera:{position:[0,.4,8.7],fov:43},dpr:[1,1.35],shadows:true,gl:{antialias:true,alpha:false,powerPreference:"high-performance"},onCreated:configureRenderer(1.02),style:{background:"#070609"}},
    h(AdaptiveCamera,{desktop:[0,.4,8.7],mobile:[0,.28,11.8],desktopFov:43,mobileFov:47}),h(StudioEnvironment,{accent:profile.accent,intensity:.7}),h("group",{key:signal.version},h(CaptureVolume,{mode:signal.mode,operator:true})),h(ContactShadows,{position:[0,-2.18,0],opacity:.52,scale:12,blur:3,far:4,color:"#020203"})
  );
}

function SceneEntrance({children}){
  const group=useRef();
  useFrame((_,delta)=>{if(!group.current)return;group.current.scale.x=THREE.MathUtils.damp(group.current.scale.x,1,5,delta);group.current.scale.y=THREE.MathUtils.damp(group.current.scale.y,1,5,delta);group.current.scale.z=THREE.MathUtils.damp(group.current.scale.z,1,5,delta);group.current.rotation.y=THREE.MathUtils.damp(group.current.rotation.y,0,4,delta)});
  return h("group",{ref:group,scale:reduced?1:.74,rotation:[0,.32,0]},children);
}

function MicroPixelArray({columns=28,rows=18,size=.045,gap=.055,color="#8870ff"}){
  const mesh=useRef(),dummy=useMemo(()=>new THREE.Object3D(),[]),shade=useMemo(()=>new THREE.Color(),[]);
  useLayoutEffect(()=>{if(!mesh.current)return;for(let index=0;index<columns*rows;index++){const x=index%columns,y=Math.floor(index/columns),t=index/(columns*rows-1);dummy.position.set((x-(columns-1)/2)*gap,((rows-1)/2-y)*gap,0);dummy.updateMatrix();mesh.current.setMatrixAt(index,dummy.matrix);shade.set(t%3<1?color:t%3<2?"#4d68a8":"#7b4f73");mesh.current.setColorAt(index,shade)}mesh.current.instanceMatrix.needsUpdate=true;if(mesh.current.instanceColor)mesh.current.instanceColor.needsUpdate=true},[mesh,dummy,shade,columns,rows,gap,color]);
  return h("instancedMesh",{ref:mesh,args:[null,null,columns*rows]},h("boxGeometry",{args:[size,size,.018]}),h("meshPhysicalMaterial",{vertexColors:true,metalness:.38,roughness:.16,clearcoat:.7}));
}

function SensorAssembly({position=[0,0,0],rotation=[0,0,0],scale=1,scanRef}){
  return h("group",{position,rotation,scale},
    h(RoundedBox,{args:[2.7,2.15,.34],radius:.16,smoothness:6,castShadow:true},h("meshPhysicalMaterial",{color:"#252229",metalness:.96,roughness:.15,clearcoat:.55})),
    h("mesh",{position:[0,0,.21]},h("boxGeometry",{args:[2.2,1.65,.08]}),h("meshStandardMaterial",{color:"#08080a",metalness:.72,roughness:.18})),
    h("group",{position:[0,0,.27]},h(MicroPixelArray,{columns:32,rows:22,size:.047,gap:.058,color:"#7963d3"})),
    h("mesh",{position:[0,0,.34]},h("planeGeometry",{args:[2.05,1.5]}),h("meshPhysicalMaterial",{color:"#54436e",transparent:true,opacity:.22,transmission:.5,thickness:.3,roughness:.02,iridescence:.8,depthWrite:false})),
    h("mesh",{ref:scanRef,position:[0,.7,.39]},h("boxGeometry",{args:[2.05,.035,.025]}),h("meshBasicMaterial",{color:"#d5caff",transparent:true,opacity:.9,toneMapped:false})),
    [[-1.18,.91],[1.18,.91],[-1.18,-.91],[1.18,-.91]].map((position,index)=>h("mesh",{key:index,position:[...position,.22]},h("cylinderGeometry",{args:[.06,.06,.05,32]}),h("meshStandardMaterial",{color:"#9c96a0",metalness:1,roughness:.08})))
  );
}

function ProcessorPackage({position=[0,0,0],scale=1,labelColor="#7657ff"}){
  return h("group",{position,scale},
    h(RoundedBox,{args:[2.25,2.25,.38],radius:.14,smoothness:6,castShadow:true},h("meshPhysicalMaterial",{color:"#17151a",metalness:.9,roughness:.18,clearcoat:.5})),
    h("mesh",{position:[0,0,.24]},h("boxGeometry",{args:[1.55,1.55,.12]}),h("meshPhysicalMaterial",{color:"#29212f",emissive:labelColor,emissiveIntensity:.1,metalness:.74,roughness:.12,clearcoat:.72})),
    Array.from({length:12},(_,index)=>{const angle=index/12*Math.PI*2;return h("mesh",{key:index,position:[Math.cos(angle)*.91,Math.sin(angle)*.91,.25],rotation:[0,0,angle]},h("boxGeometry",{args:[.28,.035,.03]}),h("meshStandardMaterial",{color:"#a07145",metalness:.9,roughness:.16}))}),
    h("mesh",{position:[0,0,.32]},h("ringGeometry",{args:[.38,.48,64]}),h("meshBasicMaterial",{color:labelColor,transparent:true,opacity:.52,toneMapped:false}))
  );
}

function CircuitTrace({points,color="#7657ff"}){
  const curve=useMemo(()=>new THREE.CatmullRomCurve3(points.map(point=>new THREE.Vector3(...point))),[points]);
  return h("mesh",null,h("tubeGeometry",{args:[curve,48,.018,8,false]}),h("meshStandardMaterial",{color,emissive:color,emissiveIntensity:.35,metalness:.75,roughness:.2}));
}

function FPSScene(){
  const scan=useRef(),pulseRefs=useRef([]),bufferRefs=useRef([]),lastFrame=useRef(0),point=useMemo(()=>new THREE.Vector3(),[]);
  const signalPath=useMemo(()=>new THREE.CatmullRomCurve3([new THREE.Vector3(-2.35,.05,.58),new THREE.Vector3(-1.15,.65,.48),new THREE.Vector3(.2,.2,.4),new THREE.Vector3(1.25,.05,.38),new THREE.Vector3(2.55,-.1,.32)]),[]);
  useFrame(({clock})=>{
    const time=clock.elapsedTime,phase=time%1;
    if(scan.current)scan.current.position.y=.7-phase*1.4;
    pulseRefs.current.forEach((node,index)=>{if(!node)return;const t=((index/25)+time*.34)%1;signalPath.getPointAt(t,point);node.position.copy(point);const gain=.32+Math.sin(t*Math.PI)*.85;node.scale.setScalar(gain)});
    const current=Math.floor(time*25)%25+1;bufferRefs.current.forEach((node,index)=>{if(node){const active=index<current;node.material.emissiveIntensity=active?.7:.05;node.material.opacity=active?.92:.28}});if(current!==lastFrame.current){lastFrame.current=current;const out=document.querySelector("[data-frame-counter]"),mode=document.querySelector("[data-format].is-active")?.dataset.format;if(out&&mode==="fps")out.textContent=String(current).padStart(2,"0")}
  });
  return h(SceneEntrance,null,h("group",{rotation:[-.13,-.2,.02],scale:.92},
    h("mesh",{position:[0,0,-.82]},h("boxGeometry",{args:[7.8,4.25,.18]}),h("meshPhysicalMaterial",{color:"#121115",metalness:.72,roughness:.27,clearcoat:.24})),
    h(SensorAssembly,{position:[-2.35,.05,-.34],scale:.86,scanRef:scan}),
    h(ProcessorPackage,{position:[.55,.05,-.3],scale:.82}),
    h(RoundedBox,{args:[1.42,2.4,.4],radius:.12,smoothness:5,position:[2.75,-.05,-.3]},h("meshPhysicalMaterial",{color:"#1a181d",metalness:.92,roughness:.18,clearcoat:.4})),
    Array.from({length:25},(_,index)=>{const column=index%5,row=Math.floor(index/5);return h("mesh",{key:index,ref:node=>{bufferRefs.current[index]=node},position:[2.28+column*.23,.48-row*.23,.02]},h("boxGeometry",{args:[.16,.16,.035]}),h("meshStandardMaterial",{color:"#2b2631",emissive:"#7657ff",emissiveIntensity:.05,transparent:true,opacity:.28,metalness:.48,roughness:.2}))}),
    h(CircuitTrace,{points:[[-1.25,.55,-.52],[-.65,.82,-.5],[.05,.65,-.48],[.55,.45,-.46]]}),
    h(CircuitTrace,{points:[[-1.25,-.55,-.52],[-.6,-.82,-.5],[.1,-.68,-.48],[.55,-.45,-.46]],color:"#9b8a6e"}),
    h(CircuitTrace,{points:[[1.45,.15,-.46],[1.85,.15,-.44],[2.15,.05,-.42],[2.25,.05,-.4]],color:"#b7a8ff"}),
    Array.from({length:25},(_,index)=>h("mesh",{key:index,ref:node=>{pulseRefs.current[index]=node}},h("sphereGeometry",{args:[.035,16,16]}),h("meshBasicMaterial",{color:index%5===0?"#fff4dc":"#9d86ff",toneMapped:false}))),
    Array.from({length:3},(_,index)=>h("mesh",{key:index,position:[-2.35,.05,.36+index*.18]},h("boxGeometry",{args:[2.05-index*.08,1.5-index*.06,.025]}),h("meshPhysicalMaterial",{color:index===0?"#8b6cff":index===1?"#5d6b9b":"#d8c9b2",transparent:true,opacity:.12,transmission:.7,roughness:.02,depthWrite:false})))
  ));
}

function XDRFormatScene(){
  const group=useRef(),scan=useRef();
  useFrame(({clock},delta)=>{if(scan.current)scan.current.position.y=.7-(clock.elapsedTime*.3%1)*1.4;if(!group.current||reduced)return;group.current.rotation.y=THREE.MathUtils.damp(group.current.rotation.y,-.18+Math.sin(clock.elapsedTime*.3)*.05,3,delta);group.current.position.y=Math.sin(clock.elapsedTime*.55)*.035});
  return h(SceneEntrance,null,h("group",{ref:group,rotation:[-.1,-.18,0],scale:.92},
    h("mesh",{position:[0,0,-1.05]},h("boxGeometry",{args:[8,4.6,.18]}),h("meshPhysicalMaterial",{color:"#111014",metalness:.78,roughness:.26,clearcoat:.25})),
    h(SensorAssembly,{position:[2.25,0,-.28],scale:.82,scanRef:scan}),
    Array.from({length:13},(_,index)=>{const t=index/12,x=-2.35+t*3.15;return h("group",{key:index,position:[x,(t-.5)*.28,.02+t*.035],rotation:[0,-.12+t*.1,0]},
      h("mesh",null,h("boxGeometry",{args:[2.35,3.15,.025]}),h("meshPhysicalMaterial",{color:t<.28?"#18131f":t<.72?"#67518e":"#e8dcc8",transparent:true,opacity:.045+t*.012,transmission:.72,thickness:.2,roughness:.025,clearcoat:1,side:THREE.DoubleSide,depthWrite:false})),
      h("mesh",{position:[0,-1.48,.022]},h("boxGeometry",{args:[2.35,.025,.018]}),h("meshBasicMaterial",{color:t>.8?"#f1e5d1":"#7657ff",transparent:true,opacity:.38,toneMapped:false}))
    )}),
    h("group",{position:[-3.2,.85,.25]},
      h("mesh",{rotation:[Math.PI/2,0,0]},h("cylinderGeometry",{args:[.52,.7,.65,64]}),h("meshStandardMaterial",{color:"#1b191d",metalness:.94,roughness:.16})),
      h("mesh",{position:[0,0,.38]},h("circleGeometry",{args:[.42,64]}),h("meshBasicMaterial",{color:"#fff1d7",toneMapped:false})),h("pointLight",{position:[0,0,.7],intensity:26,color:"#ffe8c8"})
    ),
    h("group",{position:[-2.9,-1.25,.12]},h(RoundedBox,{args:[1.1,.8,.75],radius:.12,smoothness:4},h("meshStandardMaterial",{color:"#080709",metalness:.74,roughness:.24})),h("mesh",{position:[0,0,.4]},h("planeGeometry",{args:[.82,.52]}),h("meshBasicMaterial",{color:"#130d20"})))
  ));
}

function OpticalElement({z,radius,thickness,color}){return h("group",{position:[0,0,z]},h("mesh",{rotation:[Math.PI/2,0,0]},h("cylinderGeometry",{args:[radius,radius,thickness,96]}),h("meshPhysicalMaterial",{color,transmission:.78,thickness:.6,ior:1.52,roughness:.035,metalness:0,transparent:true,opacity:.72,clearcoat:1,iridescence:.4})),h("mesh",null,h("torusGeometry",{args:[radius,.045,20,96]}),h("meshStandardMaterial",{color:"#413b48",metalness:1,roughness:.15})))}
function LensScene(){const group=useRef(),iris=useRef();useFrame(({clock},delta)=>{if(group.current&&!reduced)group.current.rotation.y+=delta*.08;if(iris.current&&!reduced)iris.current.rotation.z=Math.sin(clock.elapsedTime*.7)*.32});return h(SceneEntrance,null,h("group",{ref:group,rotation:[0,-.48,0]},h(OpticalElement,{z:-1.65,radius:1.42,thickness:.14,color:"#584c79"}),h(OpticalElement,{z:-.72,radius:1.2,thickness:.2,color:"#8674b9"}),h(OpticalElement,{z:.15,radius:.92,thickness:.16,color:"#b5a8de"}),h(OpticalElement,{z:1.05,radius:1.08,thickness:.18,color:"#64548f"}),h(OpticalElement,{z:1.88,radius:1.34,thickness:.15,color:"#9585c9"}),h("group",{ref:iris,position:[0,0,.55]},Array.from({length:9},(_,index)=>{const angle=index/9*Math.PI*2;return h(RoundedBox,{key:index,args:[1.05,.4,.045],radius:.12,smoothness:3,position:[Math.cos(angle)*.55,Math.sin(angle)*.55,0],rotation:[0,0,angle+.68]},h("meshStandardMaterial",{color:"#151318",metalness:.92,roughness:.22}))})),h("mesh",{position:[0,0,-2.2]},h("torusGeometry",{args:[1.62,.17,24,96]}),h("meshStandardMaterial",{color:"#201d23",metalness:1,roughness:.16})),h("mesh",{position:[0,0,2.38]},h("torusGeometry",{args:[1.58,.15,24,96]}),h("meshStandardMaterial",{color:"#29242f",metalness:1,roughness:.14}))))}

function BitScene(){
  const group=useRef(),scan=useRef(),layerRefs=useRef([]);
  useFrame(({clock},delta)=>{const phase=clock.elapsedTime*.24%1;if(scan.current)scan.current.position.y=1.1-phase*2.2;layerRefs.current.forEach((layer,index)=>{if(layer)layer.material.opacity=.035+Math.max(0,Math.sin(clock.elapsedTime*.8-index*.28))*.055});if(!group.current||reduced)return;group.current.rotation.y=THREE.MathUtils.damp(group.current.rotation.y,-.32+Math.sin(clock.elapsedTime*.24)*.055,3,delta);group.current.rotation.x=THREE.MathUtils.damp(group.current.rotation.x,-.22+Math.cos(clock.elapsedTime*.22)*.035,3,delta)});
  return h(SceneEntrance,null,h("group",{ref:group,rotation:[-.22,-.32,0],scale:.92},
    h(RoundedBox,{args:[4.2,4.2,.46],radius:.18,smoothness:7,castShadow:true},h("meshPhysicalMaterial",{color:"#18161b",metalness:.94,roughness:.17,clearcoat:.46})),
    h("mesh",{position:[0,0,.3]},h("boxGeometry",{args:[3.25,3.25,.12]}),h("meshPhysicalMaterial",{color:"#241c2e",emissive:"#241447",emissiveIntensity:.12,metalness:.72,roughness:.13,clearcoat:.72})),
    h("group",{position:[0,0,.39]},h(MicroPixelArray,{columns:32,rows:32,size:.058,gap:.075,color:"#8e77ff"})),
    h("mesh",{ref:scan,position:[0,1.1,.45]},h("boxGeometry",{args:[2.48,.035,.025]}),h("meshBasicMaterial",{color:"#efe7ff",transparent:true,opacity:.82,toneMapped:false})),
    Array.from({length:10},(_,index)=>h("mesh",{key:index,ref:node=>{layerRefs.current[index]=node},position:[0,0,.56+index*.11]},h("boxGeometry",{args:[2.8-index*.035,2.8-index*.035,.018]}),h("meshPhysicalMaterial",{color:index<3?"#6c5b91":index<7?"#8b73c8":"#d8cce5",transparent:true,opacity:.055,transmission:.74,roughness:.015,depthWrite:false}))),
    Array.from({length:20},(_,index)=>{const angle=index/20*Math.PI*2;return h("mesh",{key:index,position:[Math.cos(angle)*1.82,Math.sin(angle)*1.82,.34],rotation:[0,0,angle]},h("boxGeometry",{args:[.38,.055,.035]}),h("meshStandardMaterial",{color:"#a57648",metalness:.92,roughness:.14}))})
  ));
}

function FormatExperience({host}){
  const [mode,setMode]=useState(()=>document.querySelector("[data-format].is-active")?.dataset.format||"fps");
  const active=useStageActivity(host);
  useEffect(()=>{const listener=event=>setMode(event.detail);addEventListener("camx-format",listener);return()=>removeEventListener("camx-format",listener)},[]);
  const scene=mode==="fps"?h(FPSScene):mode==="xdr"?h(XDRFormatScene):mode==="lens"?h(LensScene):h(BitScene);
  return h(Canvas,{frameloop:active?"always":"never",camera:{position:[0,.35,8.8],fov:38},dpr:[1,1.45],gl:{antialias:true,alpha:true,powerPreference:"high-performance"},onCreated:configureRenderer(1.08),style:{background:"transparent"}},h(AdaptiveCamera,{desktop:[0,.35,8.8],mobile:[0,.25,11.5],desktopFov:38,mobileFov:45}),h(StudioEnvironment,{intensity:.9}),h("group",{key:mode},scene),h(ContactShadows,{position:[0,-2.15,0],opacity:.45,scale:10,blur:2.4,far:4,color:"#08060d"}),h(OrbitControls,{enablePan:false,enableZoom:false,minPolarAngle:Math.PI*.32,maxPolarAngle:Math.PI*.68,rotateSpeed:.55}));
}

function XDRExperience({host}){
  const active=useStageActivity(host);
  return h(Canvas,{frameloop:active?"always":"never",camera:{position:[0,.35,8.7],fov:42},dpr:[1,1.4],shadows:true,gl:{antialias:true,alpha:false,powerPreference:"high-performance"},onCreated:configureRenderer(1.12),style:{background:"#08070a"}},h(AdaptiveCamera,{desktop:[0,.35,8.7],mobile:[0,.25,11.7],desktopFov:42,mobileFov:47}),h(StudioEnvironment,{accent:"#8062ff",intensity:.78}),h(CaptureVolume,{mode:"xdr-photo"}),h(ContactShadows,{position:[0,-2.18,0],opacity:.5,scale:12,blur:3,far:4,color:"#020203"}),h(OrbitControls,{enablePan:false,enableZoom:false,minPolarAngle:Math.PI*.36,maxPolarAngle:Math.PI*.62,minAzimuthAngle:-.24,maxAzimuthAngle:.24,rotateSpeed:.3}));
}

async function mountStage(id,Component,{immediate=false}={}){
  const node=document.getElementById(id);if(!node)return;
  if(!webglReady){showStageError(node,"3D REQUIRES WEBGL / ENABLE HARDWARE ACCELERATION");return}
  await document.fonts.ready;
  const render=()=>{if(node.dataset.mounted)return;node.dataset.mounted="true";createRoot(node).render(h(Component,{host:node}))};
  if(immediate||!("IntersectionObserver" in window)){render();return}
  const observer=new IntersectionObserver(entries=>{if(entries.some(entry=>entry.isIntersecting)){observer.disconnect();render()}},{rootMargin:"480px 0px"});observer.observe(node);
}

function initScroll(){if(reduced||!Lenis||!gsap)return;const lenis=new Lenis({duration:1.05,smoothWheel:true,wheelMultiplier:.9,touchMultiplier:1.15});lenis.on("scroll",ScrollTrigger?.update);gsap.ticker.add(time=>lenis.raf(time*1000));gsap.ticker.lagSmoothing(0);document.querySelectorAll('a[href^="#"]').forEach(anchor=>anchor.addEventListener("click",event=>{const target=document.querySelector(anchor.getAttribute("href"));if(!target)return;event.preventDefault();lenis.scrollTo(target,{offset:-64})}))}
function initMotion(){if(!gsap||!ScrollTrigger||reduced)return;gsap.registerPlugin(ScrollTrigger);gsap.timeline({defaults:{ease:"power4.out"},delay:.1}).fromTo(".hero-line>span",{yPercent:110},{yPercent:0,duration:1.1,stagger:.13}).fromTo(".hero .reveal",{y:20,opacity:0},{y:0,opacity:1,duration:.75,stagger:.08},"-=.7").fromTo(".hero__stage",{opacity:0,scale:.93,rotate:3},{opacity:1,scale:1,rotate:0,duration:1.35},"-=1");gsap.utils.toArray(".section .reveal,.field .reveal").forEach(element=>gsap.fromTo(element,{y:36,opacity:0},{y:0,opacity:1,duration:.85,ease:"power3.out",scrollTrigger:{trigger:element,start:"top 88%",once:true}}));gsap.to(".hero__stage",{yPercent:10,rotate:-1.5,ease:"none",scrollTrigger:{trigger:".hero",start:"top top",end:"bottom top",scrub:1}});gsap.to(".progress i",{scaleX:1,ease:"none",scrollTrigger:{trigger:document.documentElement,start:"top top",end:"bottom bottom",scrub:.15}});document.querySelectorAll("[data-count]").forEach(element=>{const state={value:0};gsap.to(state,{value:Number(element.dataset.count),duration:1.3,snap:{value:1},onUpdate:()=>element.textContent=Math.round(state.value),scrollTrigger:{trigger:element,start:"top 90%",once:true}})})}
function initControls(){
  document.querySelectorAll("[data-control]").forEach(button=>button.addEventListener("click",()=>{const values=button.dataset.values.split(","),out=button.querySelector("strong"),next=(values.indexOf(out.textContent)+1)%values.length;out.textContent=values[next];button.classList.add("is-active");setTimeout(()=>button.classList.remove("is-active"),280);dispatchEvent(new CustomEvent("camx-operator-control",{detail:{control:button.dataset.control,value:values[next]}}))}));
  const monitor=document.querySelector("[data-monitor]"),record=document.querySelector("[data-record]");record?.addEventListener("click",()=>{const on=monitor.classList.toggle("is-recording");monitor.querySelector(".record-state").textContent=on?"● REC":"STBY";dispatchEvent(new CustomEvent("camx-operator-control",{detail:{control:"REC",value:on?"RECORD":"STANDBY"}}))});
}
const formatData={
  fps:{code:"CADENCE_25",kicker:"CADENCE",value:"25 FPS",detail:"40.00 MS / FRAME",index:"FORMAT 01 / 04",title:"Twenty-five sensor readouts. Every second.",body:"A modeled sensor, scan line, image processor, frame buffer, and encoder route make the 40 ms capture interval visible.",specs:[["FRAME INTERVAL","40.00 ms"],["SENSOR READOUT","25 cycles / second"],["DELIVERY","Device-qualified"]]},
  xdr:{code:"LATITUDE_XDR",kicker:"DYNAMIC RANGE",value:"CAMX XDR",detail:"SHADOW → HIGHLIGHT",index:"FORMAT 02 / 04",title:"Exposure latitude, held in layers.",body:"Thirteen translucent exposure planes separate deep shadow information from diffuse white and specular highlight energy.",specs:[["PROFILE","CamX XDR"],["MAPPING","Scene → display"],["PRIORITY","Highlight character"]]},
  lens:{code:"OPTIC_26MM",kicker:"FIELD OF VIEW",value:"26 MM",detail:"WIDE / DEVICE NATIVE",index:"FORMAT 03 / 04",title:"A lens is a sequence, not a circle.",body:"Five physically based glass elements separate to reveal the optical path, coatings, barrel, and animated nine-blade aperture.",specs:[["EQUIVALENT","26 mm wide"],["ELEMENTS","5 modeled groups"],["APERTURE","9-blade study"]]},
  bit:{code:"SIGNAL_1024",kicker:"CODE VALUES",value:"10-BIT",detail:"1,024 LEVELS / CHANNEL",index:"FORMAT 04 / 04",title:"One thousand and twenty-four tonal decisions.",body:"A volumetric field of 1,024 individually positioned samples represents the precision available to each encoded color channel.",specs:[["LEVELS","1,024 / channel"],["PRECISION","10-bit"],["ROUTE","P010 when supported"]]}
};
function initFormatTabs(){
  const root=document.querySelector("[data-format-console]");if(!root)return;const copy=root.querySelector("[data-format-copy]"),buttons=[...root.querySelectorAll("[data-format]")],readout={code:root.querySelector("[data-stage-code]"),kicker:root.querySelector("[data-readout-kicker]"),value:root.querySelector("[data-readout-value]"),detail:root.querySelector("[data-readout-detail]")};
  const activate=(button,animate=true)=>{const mode=button.dataset.format,data=formatData[mode];if(!data)return;buttons.forEach(item=>{const active=item===button;item.classList.toggle("is-active",active);item.setAttribute("aria-selected",String(active));item.tabIndex=active?0:-1});copy.setAttribute("aria-labelledby",button.id);const apply=()=>{readout.code.textContent=data.code;readout.kicker.textContent=data.kicker;readout.value.textContent=data.value;readout.detail.textContent=data.detail;copy.querySelector(".format-copy__index").textContent=data.index;copy.querySelector("h3").textContent=data.title;copy.querySelector(":scope > p:not(.format-copy__index)").textContent=data.body;copy.querySelectorAll("dl div").forEach((row,index)=>{row.querySelector("dt").textContent=data.specs[index][0];row.querySelector("dd").textContent=data.specs[index][1]});dispatchEvent(new CustomEvent("camx-format",{detail:mode}));const counter=root.querySelector(".stage-counter"),counterData={fps:["01","25"],xdr:["13","PLANES"],lens:["05","GROUPS"],bit:["1,024","LEVELS"]}[mode];if(counter){counter.querySelector("[data-frame-counter]").textContent=counterData[0];counter.querySelector("span:last-child").textContent=counterData[1]}};if(animate&&gsap&&!reduced)gsap.to([copy,root.querySelector(".stage-readout")],{opacity:0,y:8,duration:.16,overwrite:true,onComplete:()=>{apply();gsap.to([copy,root.querySelector(".stage-readout")],{opacity:1,y:0,duration:.34,ease:"power2.out",overwrite:true})}});else apply()};
  buttons.forEach((button,index)=>{button.addEventListener("click",()=>activate(button));button.addEventListener("keydown",event=>{let next;if(event.key==="ArrowRight"||event.key==="ArrowDown")next=(index+1)%buttons.length;else if(event.key==="ArrowLeft"||event.key==="ArrowUp")next=(index-1+buttons.length)%buttons.length;else if(event.key==="Home")next=0;else if(event.key==="End")next=buttons.length-1;else return;event.preventDefault();buttons[next].focus();activate(buttons[next])})});const requested=new URLSearchParams(location.search).get("format"),initial=buttons.find(button=>button.dataset.format===requested);if(initial){activate(initial,false);requestAnimationFrame(()=>document.getElementById("formats")?.scrollIntoView({block:"start"}))}
}
function initInteractions(){if(!motionAnimate)return;document.querySelectorAll(".magnetic").forEach(element=>{element.addEventListener("pointermove",event=>{if(reduced||event.pointerType==="touch")return;const rect=element.getBoundingClientRect();motionAnimate(element,{x:(event.clientX-rect.left-rect.width/2)*.1,y:(event.clientY-rect.top-rect.height/2)*.1},{type:"spring",stiffness:320,damping:24})});element.addEventListener("pointerleave",()=>motionAnimate(element,{x:0,y:0},{type:"spring",stiffness:300,damping:22}))})}
function initNav(){const header=document.querySelector("[data-header]"),button=document.querySelector("[data-menu]"),nav=document.querySelector("[data-mobile-nav]");addEventListener("scroll",()=>header?.classList.toggle("is-scrolled",scrollY>24),{passive:true});const close=()=>{nav?.classList.remove("is-open");nav?.setAttribute("aria-hidden","true");button?.setAttribute("aria-expanded","false");document.body.classList.remove("menu-open")};button?.addEventListener("click",()=>{const open=!nav.classList.contains("is-open");nav.classList.toggle("is-open",open);nav.setAttribute("aria-hidden",String(!open));button.setAttribute("aria-expanded",String(open));document.body.classList.toggle("menu-open",open)});nav?.querySelectorAll("a").forEach(anchor=>anchor.addEventListener("click",close))}

initNav();
await Promise.all([mountStage("camx-device",DeviceExperience,{immediate:true}),mountStage("operator-3d-stage",OperatorExperience),mountStage("format-stage",FormatExperience),mountStage("xdr-3d-stage",XDRExperience)]);
initScroll();initMotion();initControls();initFormatTabs();initInteractions();
})().catch(error=>{
  console.error("CamX 3D runtime failed",error);
  document.querySelectorAll("#camx-device,#operator-3d-stage,#format-stage,#xdr-3d-stage").forEach(stage=>showStageError(stage,location.protocol==="file:"?"3D COULD NOT LOAD / CHECK INTERNET ACCESS":"3D COULD NOT LOAD / RELOAD THE PAGE"));
});
