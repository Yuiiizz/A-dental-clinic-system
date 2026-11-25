import { beforeEach, describe, expect, it, vi } from 'vitest';

const flow=vi.hoisted(()=>({hasSession:true,sessionRevision:1}));
const changes=vi.hoisted(()=>vi.fn());
vi.mock('../miniprogram/services/auth-flow',()=>({authFlow:flow}));
vi.mock('../miniprogram/services/booking-api',()=>({createRealBookingApi:()=>({changes})}));

async function home() {
  let config: Record<string,unknown> | null=null;
  vi.stubGlobal('Page',(value:Record<string,unknown>)=>{config=value;});
  vi.stubGlobal('wx',{navigateTo:vi.fn(),showToast:vi.fn(),getStorageSync:vi.fn().mockReturnValue('0')});
  vi.resetModules();
  await import('../miniprogram/pages/home/home');
  if(!config) throw new Error('home page missing');
  const source=config as Record<string,unknown> & {data:Record<string,unknown>};
  const page={data:{...source.data},setData(patch:Record<string,unknown>){Object.assign(this.data,patch);}} as
    {data:Record<string,unknown>;setData:(value:Record<string,unknown>)=>void;onShow:()=>void;onHide:()=>void};
  for(const [name,value] of Object.entries(source))
    if(typeof value==='function') (page as unknown as Record<string,unknown>)[name]=value.bind(page);
  return page;
}

async function flush() { await new Promise(resolve=>setTimeout(resolve,0)); }

describe('首页真实协调动态',()=>{
  beforeEach(()=>{changes.mockReset();flow.hasSession=true;flow.sessionRevision=1;});

  it('只显示本人真实方案，待答复优先并保留非推送语义',async()=>{
    changes.mockResolvedValue([
      {change_id:'9',state:'已撤回',reason:'旧候选不可用'},
      {change_id:'8',state:'待答复',reason:'医生临时停诊'},
    ]);
    const page=await home();page.onShow();await flush();
    expect(page.data.changeSummary).toEqual({id:'8',state:'待答复',reason:'医生临时停诊',pendingCount:1});
    expect(page.data.changeError).toBe('');
  });

  it('隐藏期间旧请求和换会话后的结果不能复活',async()=>{
    let resolve!: (value:unknown[])=>void;
    changes.mockImplementationOnce(()=>new Promise<unknown[]>(done=>{resolve=done;}));
    const page=await home();page.onShow();page.onHide();
    flow.sessionRevision=2;
    resolve([{change_id:'8',state:'待答复',reason:'旧账号资料'}]);
    await flush();
    expect(page.data.changeSummary).toBeNull();
    flow.hasSession=false;page.onShow();await flush();
    expect(page.data.changeSummary).toBeNull();
    expect(changes).toHaveBeenCalledTimes(1);
  });
});
