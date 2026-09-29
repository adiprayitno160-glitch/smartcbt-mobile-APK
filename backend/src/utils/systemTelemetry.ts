import os from 'os';

// ========================================================
// REAL-TIME ACCURATE CPU TELEMETRY (WINDOWS & LINUX SUPPORT)
// ========================================================

let previousCpus = os.cpus();
let cachedCpuPercent = 12;
let cachedPerCorePercents: number[] = [];

function sampleCpuUsage() {
    try {
        const currentCpus = os.cpus();
        let totalIdle = 0;
        let totalTick = 0;
        const coreLoads: number[] = [];

        for (let i = 0; i < currentCpus.length; i++) {
            const prev = previousCpus[i];
            const curr = currentCpus[i];
            if (!prev) continue;

            let prevTick = 0;
            let currTick = 0;
            for (const type in prev.times) prevTick += (prev.times as any)[type];
            for (const type in curr.times) currTick += (curr.times as any)[type];

            const deltaIdle = curr.times.idle - prev.times.idle;
            const deltaTotal = currTick - prevTick;

            let corePercent = 0;
            if (deltaTotal > 0) {
                corePercent = Math.max(0, Math.min(100, Math.round((1 - deltaIdle / deltaTotal) * 100)));
            }
            coreLoads.push(corePercent);

            totalIdle += deltaIdle;
            totalTick += deltaTotal;
        }

        if (totalTick > 0) {
            cachedCpuPercent = Math.max(1, Math.min(100, Math.round((1 - totalIdle / totalTick) * 100)));
        }
        cachedPerCorePercents = coreLoads;
        previousCpus = currentCpus;
    } catch (e) {
        console.warn('[Telemetry] Error sampling CPU usage:', e);
    }
}

// Continuous sampler running every 1000ms
setInterval(sampleCpuUsage, 1000);
// Warmup sample immediately
setTimeout(sampleCpuUsage, 150);

export function getCpuTelemetry() {
    const cpus = os.cpus();
    const rawModel = cpus.length > 0 ? cpus[0].model.trim() : 'Intel(R) Core(TM) i3-4160 CPU @ 3.60GHz';
    // Clean up excessive spaces in model name
    const model = rawModel.replace(/\s+/g, ' ');
    const cores = cpus.length;
    const speedMhz = cpus.length > 0 ? cpus[0].speed : 3600;
    const speedGhz = `${(speedMhz / 1000).toFixed(2)} GHz`;

    const usagePercent = cachedCpuPercent;
    const perCore = cachedPerCorePercents.length > 0 
        ? cachedPerCorePercents 
        : Array.from({ length: cores }, () => usagePercent);

    let status = 'NORMAL';
    if (usagePercent >= 85) status = 'KRITIS';
    else if (usagePercent >= 70) status = 'WASPADA';

    return {
        model,
        cores,
        speedMhz,
        speedGhz,
        usagePercent,
        perCore,
        status
    };
}
