(()=>{
  "use strict";

  let started=false;
  const loadExperience=()=>{
    if(started)return;
    started=true;
    const script=document.createElement("script");
    script.src="app.bundle.js";
    script.async=true;
    script.dataset.camxRuntime="";
    script.addEventListener("load",()=>document.documentElement.classList.add("experience-ready"),{once:true});
    script.addEventListener("error",()=>{
      document.documentElement.classList.add("experience-unavailable");
      document.querySelectorAll("#camx-device,#operator-3d-stage,#format-stage,#xdr-3d-stage").forEach(stage=>{
        const status=stage.querySelector(".stage-loader");
        if(status){status.classList.add("stage-loader--error");status.textContent="3D EXPERIENCE UNAVAILABLE / CORE SITE REMAINS ACTIVE"}
      });
    },{once:true});
    document.body.append(script);
  };

  const schedule=()=>{
    if("requestIdleCallback" in window)requestIdleCallback(loadExperience,{timeout:1100});
    else setTimeout(loadExperience,180);
  };

  if(document.readyState==="complete")schedule();
  else addEventListener("load",schedule,{once:true});

  ["pointerdown","keydown","touchstart"].forEach(type=>addEventListener(type,loadExperience,{once:true,passive:true}));
})();
