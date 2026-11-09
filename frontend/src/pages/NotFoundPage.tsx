import { Link } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { LogoMark } from '../components/icons'
import { ReturnStamp } from '../components/ReturnStamp'

/** 없는 주소. 서류가 반송되듯 도장 하나로 알려 준다 */
export function NotFoundPage() {
  const { user } = useAuth()
  return (
    <div className="nf">
      <Link to={user ? '/checks' : '/'} className="nf-brand"><LogoMark size={22} /> FTA 원산지 점검</Link>
      <div className="nf-body">
        <ReturnStamp code="404" />
        <h1 className="nf-title">찾는 페이지가 없습니다</h1>
        <p className="nf-line">주소가 바뀌었거나 삭제된 심사일 수 있어요.</p>
        <Link to={user ? '/checks' : '/'} className="btn btn-primary btn-lg">{user ? '심사목록으로' : '처음 화면으로'}</Link>
      </div>
    </div>
  )
}
