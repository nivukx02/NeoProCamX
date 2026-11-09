(()=>{
  "use strict";

  const menuButton=document.querySelector("[data-menu]");
  const mobileNav=document.querySelector("[data-mobile-nav]");

  function syncMenu({moveFocus=false,restoreFocus=false}={}){
    if(!menuButton||!mobileNav)return;
    const open=menuButton.getAttribute("aria-expanded")==="true";
    mobileNav.inert=!open;
    menuButton.setAttribute("aria-label",open?"Close menu":"Open menu");
    if(moveFocus&&open)requestAnimationFrame(()=>mobileNav.querySelector("a")?.focus());
    if(restoreFocus&&!open)menuButton.focus();
  }

  syncMenu();
  menuButton?.addEventListener("click",()=>requestAnimationFrame(()=>syncMenu({moveFocus:menuButton.getAttribute("aria-expanded")==="true"})));
  mobileNav?.querySelectorAll("a").forEach(link=>link.addEventListener("click",()=>requestAnimationFrame(()=>syncMenu())));

  document.addEventListener("keydown",event=>{
    if(!mobileNav||mobileNav.getAttribute("aria-hidden")!=="false")return;
    if(event.key==="Escape"){
      event.preventDefault();
      mobileNav.classList.remove("is-open");
      mobileNav.setAttribute("aria-hidden","true");
      menuButton?.setAttribute("aria-expanded","false");
      document.body.classList.remove("menu-open");
      syncMenu({restoreFocus:true});
      return;
    }
    if(event.key!=="Tab")return;
    const focusable=[menuButton,...mobileNav.querySelectorAll("a")].filter(Boolean);
    const first=focusable[0],last=focusable[focusable.length-1];
    if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus()}
    else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus()}
  });

  const recordButton=document.querySelector("[data-record]");
  const monitor=document.querySelector("[data-monitor]");
  recordButton?.addEventListener("click",()=>{
    const recording=monitor?.classList.contains("is-recording")||false;
    recordButton.setAttribute("aria-pressed",String(recording));
    recordButton.setAttribute("aria-label",recording?"Stop recording":"Start recording");
  });

  document.querySelectorAll('a[target="_blank"]').forEach(link=>{
    link.rel="noopener noreferrer";
    const label=link.getAttribute("aria-label")||link.textContent.trim();
    if(!/new tab/i.test(label))link.setAttribute("aria-label",`${label} (opens in a new tab)`);
  });

  const desktopLinks=[...document.querySelectorAll('.header nav a[href^="#"]')];
  const mobileLinks=[...document.querySelectorAll('.mobile-nav a[href^="#"]')];
  const trackedLinks=[...desktopLinks,...mobileLinks];
  const trackedSections=[...new Set(desktopLinks.map(link=>document.querySelector(link.getAttribute("href"))).filter(Boolean))];
  if("IntersectionObserver" in window&&trackedSections.length){
    const visible=new Map();
    const updateCurrent=()=>{
      const current=[...visible.entries()].filter(([,entry])=>entry.isIntersecting).sort((a,b)=>b[1].intersectionRatio-a[1].intersectionRatio)[0]?.[0];
      if(!current)return;
      trackedLinks.forEach(link=>{
        const active=link.getAttribute("href")===`#${current}`;
        link.classList.toggle("is-current",active);
        if(active)link.setAttribute("aria-current","location");else link.removeAttribute("aria-current");
      });
    };
    const observer=new IntersectionObserver(entries=>{entries.forEach(entry=>visible.set(entry.target.id,entry));updateCurrent()},{rootMargin:"-24% 0px -58%",threshold:[0,.1,.25,.5]});
    trackedSections.forEach(section=>observer.observe(section));
  }

  if(!window.gsap){
    const progress=document.querySelector(".progress i");
    const updateProgress=()=>{
      const distance=document.documentElement.scrollHeight-innerHeight;
      progress?.style.setProperty("transform",`scaleX(${distance>0?Math.min(1,scrollY/distance):0})`);
    };
    addEventListener("scroll",updateProgress,{passive:true});
    addEventListener("resize",updateProgress,{passive:true});
    updateProgress();
  }
})();
