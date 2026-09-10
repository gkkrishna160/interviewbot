import { useState } from 'react';
import { Home } from './pages/Home';
import { InterviewReport } from './pages/InterviewReport';
import { InterviewScreen } from './pages/InterviewScreen';
import { InterviewSetup } from './pages/InterviewSetup';
import { InterviewResponse, QuestionResponse } from './types/interview';

type Page = 'home' | 'setup' | 'interview' | 'report';

function App() {
  const [page, setPage] = useState<Page>('home');
  const [interview, setInterview] = useState<InterviewResponse | null>(null);
  const [question, setQuestion] = useState<QuestionResponse | null>(null);

  return (
    <main className="app-shell">
      {page === 'home' && <Home onStart={() => setPage('setup')} />}
      {page === 'setup' && (
        <InterviewSetup
          onInterviewStarted={(created, firstQuestion) => {
            setInterview(created);
            setQuestion(firstQuestion);
            setPage('interview');
          }}
        />
      )}
      {page === 'interview' && interview && question && (
        <InterviewScreen
          interview={interview}
          initialQuestion={question}
          onReport={() => setPage('report')}
          onInterviewUpdated={setInterview}
        />
      )}
      {page === 'report' && interview && <InterviewReport interviewId={interview.id} onNew={() => setPage('setup')} />}
    </main>
  );
}

export default App;
