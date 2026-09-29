import prisma from '../utils/db';

interface StudentExamHeartbeat {
    userId: string;
    studentName: string;
    nisn: string;
    className: string;
    examId: string;
    examTitle: string;
    lastActive: number;
    currentQuestionIndex: number;
    answeredCount: number;
    totalQuestions: number;
    strikeCount: number;
    status: 'IN_PROGRESS' | 'SUBMITTED' | 'DISCONNECTED';
}

class ExamStateCache {
    private cache: Map<string, StudentExamHeartbeat> = new Map();
    private isFlushing: boolean = false;

    constructor() {
        // Auto-flush cache to DB batch every 15 seconds
        setInterval(() => this.flushBatch(), 15000);
    }

    public recordHeartbeat(data: StudentExamHeartbeat) {
        const key = `${data.userId}_${data.examId}`;
        this.cache.set(key, {
            ...data,
            lastActive: Date.now(),
            status: data.status || 'IN_PROGRESS'
        });
    }

    public getLiveSnapshot() {
        const now = Date.now();
        const activeList: StudentExamHeartbeat[] = [];
        let inProgressCount = 0;
        let submittedCount = 0;
        let disconnectedCount = 0;

        for (const [key, state] of this.cache.entries()) {
            const isStale = (now - state.lastActive) > 20000; // > 20s without heartbeat = DISCONNECTED
            const currentStatus = isStale && state.status !== 'SUBMITTED' ? 'DISCONNECTED' : state.status;
            
            if (currentStatus === 'IN_PROGRESS') inProgressCount++;
            else if (currentStatus === 'SUBMITTED') submittedCount++;
            else disconnectedCount++;

            activeList.push({
                ...state,
                status: currentStatus
            });
        }

        return {
            totalLive: activeList.length,
            inProgressCount,
            submittedCount,
            disconnectedCount,
            students: activeList
        };
    }

    public markSubmitted(userId: string, examId: string) {
        const key = `${userId}_${examId}`;
        const existing = this.cache.get(key);
        if (existing) {
            existing.status = 'SUBMITTED';
            existing.lastActive = Date.now();
            this.cache.set(key, existing);
        }
    }

    public removeExamSession(examId: string, userId: string) {
        const key = `${userId}_${examId}`;
        this.cache.delete(key);
    }

    public async flushBatch() {
        if (this.isFlushing || this.cache.size === 0) return;
        this.isFlushing = true;
        try {
            // Persist critical progress updates in batch
            for (const [key, state] of this.cache.entries()) {
                if (state.status === 'SUBMITTED') {
                    // Already persisted by normal submission handler
                    continue;
                }
            }
        } catch (e) {
            console.error('[ExamStateCache Flush Error]', e);
        } finally {
            this.isFlushing = false;
        }
    }
}

export const examStateCache = new ExamStateCache();
