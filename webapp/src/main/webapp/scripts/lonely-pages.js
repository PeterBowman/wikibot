$( function () {
	var $content = $( '#lonely-pages-content' ),
		$results = $( '#lonely-pages-results' ),
		$summaryLimit = $( '#lonely-pages-limit' ),
		$summaryStart = $( '#lonely-pages-start' ),
		$summaryEnd = $( '#lonely-pages-end' ),
		$timestamp = $( '#lonely-pages-timestamp' ),
		$total = $( '#lonely-pages-total' ),
		$paginators = $( '.paginator' ),
		URL = 'lonely-pages/api',
		TIMEOUT = 5000,
		currentLimit = lonelyPages.limit,
		currentOffset = lonelyPages.offset;
	
	function makeRequest( params ) {
		return $.ajax( {
			dataType: 'json',
			url: URL + params,
			timeout: TIMEOUT
		} );
	}
	
	function handlePaginators( $el, data ) {
		var $parent = $el.parent();
		
		switch ( $parent.attr( 'class' ) ) {
			case 'paginator-prev':
				currentOffset -= currentLimit;
				break;
			case 'paginator-next':
				currentOffset += currentLimit;
				break;
			case 'paginator-limits':
				currentLimit = $el.text();
				break;
		}
		
		currentOffset = Math.max( currentOffset, 0 );
		currentLimit = Math.max( currentLimit, 0 );
		
		$paginators.find( '.paginator-prev-value, .paginator-next-value' ).text( currentLimit );
		
		$paginators.find( '.paginator-prev, .paginator-next' ).each( function () {
			var $this = $( this ),
				$a = $this.find( 'a' ),
				isNext = $this.hasClass( 'paginator-next' );
			
			if ( !isNext && currentOffset === 0 || isNext && currentOffset + currentLimit > data.total ) {
				$a.replaceWith( $a.html() );
			} else {
				if ( !$a.length ) {
					$a = $( '<a>' )
						.html( $this.html() )
						.appendTo( $this.empty() );
				}
				
				$a.attr( 'href', '?' + $.param( {
					offset: currentOffset + currentLimit * ( isNext ? 1 : -1 ),
					limit: currentLimit
				} ) );
			}
		} );
		
		$paginators.find( '.paginator-limits > a' ).each( function () {
			var $this = $( this );
			
			$this.attr( 'href', '?' + $.param( {
				offset: currentOffset,
				limit: $this.text()
			} ) );
		} );
	}
	
	function updateResults( data ) {
		var arr = $.map( data.results, function ( item ) {
			return '<li><a class="wikilink" target="_blank" title="' + item +
				'" href="https://es.wiktionary.org/wiki/' + encodeURI( item ) +
				'" data-target="' + item + '">' + item + '</a></li>';
		} );
		
		//$timestamp = ...
		$total.text( data.total );
		
		$summaryLimit.text( currentLimit );
		$summaryStart.text( currentOffset + 1 );
		$summaryEnd.text( Math.min( currentOffset + currentLimit, data.total ) );
		
		data.results.length > lonelyPages.columnThreshold
			? $results.addClass( 'column-list' )
			: $results.removeClass( 'column-list' );
		
		$results.html( arr.join( '' ) ).attr( 'start', currentOffset + 1 );
	}
	
	function pushHistoryState( url ) {
		if ( !window.history ) {
			return;
		}
		
		history[url !== undefined ? 'pushState' : 'replaceState']( {
			html: $( '<dummy>' ).append( $content.clone() ).html()
		}, '', location.origin + location.pathname + ( url || location.search || '?' + $.param( {
			offset: currentOffset,
			limit: currentLimit
		} ) ) );
	}
	
	$paginators.on( 'click', 'a', function ( evt ) {
		var $this = $( this ),
			href = $this.attr( 'href' );
		
		evt.preventDefault();
		
		$content.addClass( 'content-loading' );
		
		return makeRequest( href ).then( function ( data ) {
			handlePaginators( $this, data );
			updateResults( data );
			$content.removeClass( 'content-loading' );
			pushHistoryState( href );
		},  function ( jqXHR, textStatus, errorThrown ) {
			location.search = href;
		} );
	} );
	
	window.onpopstate =  function ( evt ) {
		if ( evt.state ) {
			$content.replaceWith( $content = $( $.parseHTML( evt.state.html ) ) );
		}
	};
	
	pushHistoryState();
} );
